package com.geeksville.mesh.base.helper

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import com.geeksville.android.Logging
import com.geeksville.android.ServiceClient
import com.geeksville.concurrent.handledLaunch
import com.geeksville.mesh.*
import com.geeksville.mesh.database.MeshtasticDatabase
import com.geeksville.mesh.database.PacketRepository
import com.geeksville.mesh.model.DeviceVersion
import com.geeksville.mesh.service.*
import com.geeksville.util.toPIIString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json

class MeshServiceHelperImp(private val meshService: MeshService) : MeshServiceHelper, Logging {
    private var previousSummary: String? = null
    private val nodeDBbyID = mutableMapOf<String, NodeInfo>()

    /// A mapping of receiver class name to package name - used for explicit broadcasts
    private val clientPackages = mutableMapOf<String, String>()
    private val serviceNotifications = MeshServiceNotifications(meshService)
    private val serviceBroadcasts =
        MeshServiceBroadcasts(meshService, clientPackages) { connectionState }
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var connectionState = MeshServiceHelper.ConnectionState.DISCONNECTED

    /// A database of received packets - used only for debug log
    private var packetRepo: PacketRepository? = null

    // If we've ever read a valid region code from our device it will be here
    var curRegionValue = RadioConfigProtos.RegionCode.Unset_VALUE

    /**
     * How many nodes are currently online (including our local node)
     */
    private val numOnlineNodes get() = nodeDBbyNodeNum.values.count { it.isOnline }
    private val numNodes get() = nodeDBbyNodeNum.size
    private var locationIntervalMsec = 0L
    private fun getPrefs() = getSharedPreferences("service-prefs", Context.MODE_PRIVATE)

    companion object {

        /// Intents broadcast by MeshService

        /* @Deprecated(message = "Does not filter by port number.  For legacy reasons only broadcast for UNKNOWN_APP, switch to ACTION_RECEIVED")
        const val ACTION_RECEIVED_DATA = "$prefix.RECEIVED_DATA" */

        private fun actionReceived(portNum: String) = "$prefix.RECEIVED.$portNum"

        const val ACTION_NODE_CHANGE = "$prefix.NODE_CHANGE"
        const val ACTION_MESH_CONNECTED = "$prefix.MESH_CONNECTED"
        const val ACTION_MESSAGE_STATUS = "$prefix.MESSAGE_STATUS"

        open class NodeNotFoundException(reason: String) : Exception(reason)
        class InvalidNodeIdException : NodeNotFoundException("Invalid NodeId")
        class NodeNumNotFoundException(id: Int) : NodeNotFoundException("NodeNum not found $id")
        class IdNotFoundException(id: String) : NodeNotFoundException("ID not found $id")

        class NoRadioConfigException(message: String = "No radio settings received (is our app too old?)") :
            RadioNotConnectedException(message)

        /** We treat software update as similar to loss of comms to the regular bluetooth service (so things like sendPosition for background GPS ignores the problem */
        class IsUpdatingException :
            RadioNotConnectedException("Operation prohibited during firmware update")

        /** The minimmum firmware version we know how to talk to. We'll still be able to talk to 1.0 firmwares but only well enough to ask them to firmware update
         */
        val minFirmwareVersion = DeviceVersion("1.2.0")
    }

    override fun createIntent(): Intent {
        return Intent().setClassName(
            "com.geeksville.mesh",
            "com.geeksville.mesh.service.MeshService"
        )
    }

    /**
     * Talk to our running service and try to set a new device address.  And then immediately
     * call start on the service to possibly promote our service to be a foreground service.
     */
    override fun changeDeviceAddress(context: Context, service: IMeshService, address: String?) {
        service.setDeviceAddress(address)
        MeshService.Companion.startService(context)
    }

    /// generate a RECEIVED action filter string that includes either the portnumber as an int, or preferably a symbolic name from portnums.proto
    override fun actionReceived(portNum: Int): String {
        val portType = Portnums.PortNum.forNumber(portNum)
        val portStr = portType?.toString() ?: portNum.toString()

        return actionReceived(portStr)
    }


    private fun getSenderName(packet: DataPacket?): String {
        val name = nodeDBbyID[packet?.from]?.user?.longName
        return name ?: "Unknown username"
    }

    private val notificationSummary
        get() = when (connectionState) {
            MeshServiceHelper.ConnectionState.CONNECTED -> meshService.getString(R.string.connected_count)
                .format(
                    numOnlineNodes,
                    numNodes
                )
            MeshServiceHelper.ConnectionState.DISCONNECTED -> meshService.getString(R.string.disconnected)
            MeshServiceHelper.ConnectionState.DEVICE_SLEEP -> meshService.getString(R.string.device_sleeping)
        }

    override fun warnUserAboutLocation() {
        Toast.makeText(
            meshService,
            meshService.getString(R.string.location_disabled),
            Toast.LENGTH_LONG
        ).show()
    }

    override fun getConnectionState(): MeshServiceHelper.ConnectionState {
        return connectionState
    }

    /**
     * a periodic callback that perhaps send our position to other nodes.
     * We first check to see if our local device has already sent a position and if so, we punt until the next check.
     * This allows us to only 'fill in' with GPS positions when the local device happens to have no good GPS sats.
     */
    override fun perhapsSendPosition(
        lat: Double,
        lon: Double,
        alt: Int,
        destNum: Int,
        wantResponse: Boolean
    ): SendPosition {
        // This operation can take a while, so instead of staying in the callback (location services) context
        // do most of the work in my service thread
        serviceScope.handledLaunch {
            // if android called us too soon, just ignore

            val myInfo = localNodeInfo
            val lastSendMsec = (myInfo?.position?.time ?: 0) * 1000L
            val now = System.currentTimeMillis()
            if (now - lastSendMsec < locationIntervalMsec)
                debug("Not sending position - the local node has sent one recently...")
            else {
                sendPosition(lat, lon, alt, destNum, wantResponse)
            }
        }
    }

    /**
     * Send a position (typically from our built in GPS) into the mesh.
     * Must be called from serviceScope. Use sendPositionScoped() for direct calls.
     */
    private fun sendPosition(
        lat: Double = 0.0,
        lon: Double = 0.0,
        alt: Int = 0,
        destNum: Int = DataPacket.NODENUM_BROADCAST,
        wantResponse: Boolean = false
    ) {
        try {
            val mi = myNodeInfo
            if (mi != null) {
                debug("Sending our position/time to=$destNum lat=$lat, lon=$lon, alt=$alt")

                val position = MeshProtos.Position.newBuilder().also {
                    it.longitudeI = Position.degI(lon)
                    it.latitudeI = Position.degI(lat)

                    it.altitude = alt
                    it.time = currentSecond() // Include our current timestamp
                }.build()

                // Also update our own map for our nodenum, by handling the packet just like packets from other users
                handleReceivedPosition(mi.myNodeNum, position)

                val fullPacket =
                    newMeshPacketTo(destNum).buildMeshPacket(priority = MeshProtos.MeshPacket.Priority.BACKGROUND) {
                        // Use the new position as data format
                        portnumValue = Portnums.PortNum.POSITION_APP_VALUE
                        payload = position.toByteString()

                        this.wantResponse = wantResponse
                    }

                // send the packet into the mesh
                sendToRadio(fullPacket)
            }
        } catch (ex: BLEException) {
            warn("Ignoring disconnected radio during gps location update")
        }
    }

    override fun setLocationIntervalMsec(locationInterval: Long) {
        locationIntervalMsec = locationInterval
    }

    override fun getRadioInterfaceService(radioInterfaceService: ServiceClient<IRadioInterfaceService>): IRadioInterfaceService {
        return (if (getConnectionState() == MeshServiceHelper.ConnectionState.CONNECTED) radioInterfaceService.serviceP else null)
            ?: throw RadioNotConnectedException()
    }

    /** Send a command/packet to our radio.  But cope with the possiblity that we might start up
    before we are fully bound to the RadioInterfaceService
    @param requireConnected set to false if you are okay with using a partially connected device (i.e. during startup)
     */
    override fun sendToRadio(p: MeshProtos.ToRadio.Builder, requireConnected: Boolean) {
        val built = p.build()
        debug("Sending to radio ${built.toPIIString()}")
        val b = built.toByteArray()

        if (SoftwareUpdateService.isUpdating)
            throw MeshServiceHelperImp.Companion.IsUpdatingException()

        if (requireConnected)
            meshService.getConnectionRadio().sendToRadio(b)
        else {
            val s = meshService.radio.serviceP ?: throw RadioNotConnectedException()
            s.sendToRadio(b)
        }
    }

    override fun updateMessageNotification(message: DataPacket) =
        serviceNotifications.updateMessageNotification(
            getSenderName(message), message.bytes!!.toString(utf8)
        )

    /**
     * tell android not to kill us
     */
    override fun startForeground() {
        val a = RadioInterfaceService.getBondedDeviceAddress(this)
        val wantForeground = a != null && a != "n"

        info("Requesting foreground service=$wantForeground")

        // We always start foreground because that's how our service is always started (if we didn't then android would kill us)
        // but if we don't really need foreground we immediately stop it.
        val notification = serviceNotifications.createServiceStateNotification(
            notificationSummary
        )
        meshService.startForeground(serviceNotifications.notifyId, notification)
        if (!wantForeground) {
            meshService.stopForeground(true)
        }
    }

    override fun getPacketRepo(): PacketRepository {
        val packetsDao = MeshtasticDatabase.getDatabase(meshService.applicationContext).packetDao()
        return PacketRepository(packetsDao)
    }

    override fun handleLaunch() {
        serviceScope.handledLaunch {
            loadSettings() // Load our last known node DB

            // we listen for messages from the radio receiver _before_ trying to create the service
            val filter = IntentFilter().apply {
                addAction(RadioInterfaceService.RECEIVE_FROMRADIO_ACTION)
                addAction(RadioInterfaceService.RADIO_CONNECTED_ACTION)
            }
            registerReceiver(radioInterfaceReceiver, filter)

            // We in turn need to use the radiointerface service
            val intent = Intent(this@MeshService, RadioInterfaceService::class.java)
            // intent.action = IMeshService::class.java.name
            radio.connect(this@MeshService, intent, Context.BIND_AUTO_CREATE)

            // the rest of our init will happen once we are in radioConnection.onServiceConnected
        }
    }

    private fun loadSettings() {
        try {
            getPrefs().getString("json", null)?.let { asString ->

                val json = Json { isLenient = true }
                val settings = json.decodeFromString(MeshServiceSettingsData.serializer(), asString)
                installNewNodeDB(settings.myInfo, settings.nodeDB)
                curRegionValue = settings.regionCode

                // Note: we do not haveNodeDB = true because that means we've got a valid db from a real device (rather than this possibly stale hint)

                recentDataPackets.addAll(settings.messages)
            }
        } catch (ex: Exception) {
            errormsg("Ignoring error loading saved state for service: ${ex.message}")
        }
    }
}