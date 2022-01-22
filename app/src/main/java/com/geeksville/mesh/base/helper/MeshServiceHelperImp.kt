package com.geeksville.mesh.base.helper

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.RemoteException
import android.widget.Toast
import androidx.core.content.edit
import com.geeksville.analytics.DataPair
import com.geeksville.android.GeeksvilleApplication
import com.geeksville.android.Logging
import com.geeksville.concurrent.handledLaunch
import com.geeksville.mesh.*
import com.geeksville.mesh.common.IntentUtil
import com.geeksville.mesh.common.MeshServiceCompanion
import com.geeksville.mesh.common.MeshServiceCompanion.Companion.minFirmwareVersion
import com.geeksville.mesh.database.MeshtasticDatabase
import com.geeksville.mesh.database.PacketRepository
import com.geeksville.mesh.database.entity.Packet
import com.geeksville.mesh.model.DeviceVersion
import com.geeksville.mesh.service.*
import com.geeksville.util.anonymize
import com.geeksville.util.exceptionReporter
import com.geeksville.util.ignoreException
import com.geeksville.util.toPIIString
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.max

class MeshServiceHelperImp(
    private val meshService: MeshService
) : MeshServiceHelper {
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
    private fun getPrefs() = meshService.getSharedPreferences("service-prefs", Context.MODE_PRIVATE)


    ///
    /// BEGINNING OF MODEL - FIXME, move elsewhere
    ///


    var myNodeInfo: MyNodeInfo? = null

    private var radioConfig: RadioConfigProtos.RadioConfig? = null

    private var channels = fixupChannelList(listOf())

    /// True after we've done our initial node db init
    @Volatile
    private var haveNodeDB = false

    // The database of active nodes, index is the node number
    private val nodeDBbyNodeNum = mutableMapOf<Int, NodeInfo>()

    /// The database of active nodes, index is the node user ID string
    /// NOTE: some NodeInfos might be in only nodeDBbyNodeNum (because we don't yet know
    /// an ID).  But if a NodeInfo is in both maps, it must be one instance shared by
    /// both datastructures.

    ///
    /// END OF MODEL
    ///

    val deviceVersion get() = DeviceVersion(myNodeInfo?.firmwareVersion ?: "")

    /// Map a nodenum to a node, or throw an exception if not found
    private fun toNodeInfo(n: Int) =
        nodeDBbyNodeNum[n] ?: throw MeshServiceCompanion.Companion.NodeNumNotFoundException(
            n
        )

    /**
     * Return the nodeinfo for the local node, or null if not found
     */
    private val localNodeInfo
        get(): NodeInfo? =
            try {
                toNodeInfo(myNodeNum)
            } catch (ex: Exception) {
                null
            }

    private val hexIdRegex = """\!([0-9A-Fa-f]+)""".toRegex()

    /// My node num
    private val myNodeNum
        get() = myNodeInfo?.myNodeNum
            ?: throw RadioNotConnectedException("We don't yet have our myNodeInfo")

    /// My node ID string
    private val myNodeID get() = toNodeID(myNodeNum)

    /// Convert the channels array into a ChannelSet
    private var channelSet: AppOnlyProtos.ChannelSet
        get() {
            val cs = channels.filter {
                it.role != ChannelProtos.Channel.Role.DISABLED
            }.map {
                it.settings
            }

            return AppOnlyProtos.ChannelSet.newBuilder().apply {
                addAllSettings(cs)
            }.build()
        }
        set(value) {
            val asChannels = value.settingsList.mapIndexed { i, c ->
                ChannelProtos.Channel.newBuilder().apply {
                    role =
                        if (i == 0) ChannelProtos.Channel.Role.PRIMARY else ChannelProtos.Channel.Role.SECONDARY
                    index = i
                    settings = c
                }.build()
            }

            debug("Sending channels to device")
            asChannels.forEach {
                setChannel(it)
            }

            channels = fixupChannelList(asChannels)
        }

    // FIXME - possible kotlin bug in 1.3.72 - it seems that if we start with the (globally shared) emptyList,
    // then adding items are affecting that shared list rather than a copy.   This was causing aliasing of
    // recentDataPackets with messages.value in the GUI.  So if the current list is empty we are careful to make a new list
    private var recentDataPackets = mutableListOf<DataPacket>()


    private var sleepTimeout: Job? = null

    /// msecs since 1970 we started this connection
    private var connectTimeMsec = 0L

    /// A provisional MyNodeInfo that we will install if all of our node config downloads go okay
    private var newMyNodeInfo: MyNodeInfo? = null

    /// provisional NodeInfos we will install if all goes well
    private val newNodes = mutableListOf<MeshProtos.NodeInfo>()

    /// Used to make sure we never get foold by old BLE packets
    private var configNonce = 1

    override fun createIntent(): Intent {
        return IntentUtil.getMeshIntent()
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
    ) {
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
    override fun sendPosition(
        lat: Double,
        lon: Double,
        alt: Int,
        destNum: Int,
        wantResponse: Boolean
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

    /**
     * Send a mesh packet to the radio, if the radio is not currently connected this function will throw NotConnectedException
     */
    private fun sendToRadio(packet: MeshProtos.MeshPacket, requireConnected: Boolean = true) {
        sendToRadio(MeshProtos.ToRadio.newBuilder().apply {
            this.packet = packet
        }, requireConnected)
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
            throw MeshServiceCompanion.Companion.IsUpdatingException()

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
        val a = RadioInterfaceService.getBondedDeviceAddress(meshService)
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
            meshService.registerReceiver(meshService.getRadioInterfaceReceiver(), filter)

            // We in turn need to use the radiointerface service
            val intent = Intent(meshService, RadioInterfaceService::class.java)
            // intent.action = IMeshService::class.java.name
            meshService.radio.connect(meshService, intent, Context.BIND_AUTO_CREATE)
//            meshService.connectToRadio()
            // the rest of our init will happen once we are in radioConnection.onServiceConnected
        }
    }

    override fun serviceNotificationsClose() {
        serviceNotifications.close()
    }

    override fun cancelServiceJob() {
        serviceJob.cancel()
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

    /// Save information about our mesh to disk, so we will have it when we next start the service (even before we hear from our device)
    override fun saveSettings() {
        myNodeInfo?.let { myInfo ->
            val settings = MeshServiceSettingsData(
                myInfo = myInfo,
                nodeDB = nodeDBbyNodeNum.values.toTypedArray(),
                messages = recentDataPackets.toTypedArray(),
                regionCode = curRegionValue
            )
            val json = Json { isLenient = true }
            val asString = json.encodeToString(MeshServiceSettingsData.serializer(), settings)
            debug("Saving settings")
            getPrefs().edit(commit = true) {
                // FIXME, not really ideal to store this bigish blob in preferences
                putString("json", asString)
            }
        }
    }

    private fun installNewNodeDB(ni: MyNodeInfo, nodes: Array<NodeInfo>) {
        discardNodeDB() // Get rid of any old state

        myNodeInfo = ni

        // put our node array into our two different map representations
        nodeDBbyNodeNum.putAll(nodes.map { Pair(it.num, it) })
        nodeDBbyID.putAll(nodes.mapNotNull {
            it.user?.let { user -> // ignore records that don't have a valid user
                Pair(
                    user.id,
                    it
                )
            }
        })
    }

    /**
     * discard entire node db & message state - used when downloading a new db from the device
     */
    override fun discardNodeDB() {
        debug("Discarding NodeDB")
        myNodeInfo = null
        nodeDBbyNodeNum.clear()
        nodeDBbyID.clear()
        // recentDataPackets.clear() We do NOT want to clear this, because it is the record of old messages the GUI still might want to show
        haveNodeDB = false
    }

    /** Map a nodenum to the nodeid string, or return null if not present
    If we have a NodeInfo for this ID we prefer to return the string ID inside the user record.
    but some nodes might not have a user record at all (because not yet received), in that case, we return
    a hex version of the ID just based on the number */
    private fun toNodeID(n: Int): String? =
        if (n == DataPacket.NODENUM_BROADCAST)
            DataPacket.ID_BROADCAST
        else
            nodeDBbyNodeNum[n]?.user?.id ?: DataPacket.nodeNumToDefaultId(n)

    /// given a nodenum, return a db entry - creating if necessary
    private fun getOrCreateNodeInfo(n: Int) =
        nodeDBbyNodeNum.getOrPut(n) { -> NodeInfo(n) }


    /// Map a userid to a node/ node num, or throw an exception if not found
    /// We prefer to find nodes based on their assigned IDs, but if no ID has been assigned to a node, we can also find it based on node number
    private fun toNodeInfo(id: String): NodeInfo {
        // If this is a valid hexaddr will be !null
        val hexStr = hexIdRegex.matchEntire(id)?.groups?.get(1)?.value

        return nodeDBbyID[id] ?: when {
            id == DataPacket.ID_LOCAL -> toNodeInfo(myNodeNum)
            hexStr != null -> {
                val n = hexStr.toLong(16).toInt()
                nodeDBbyNodeNum[n] ?: throw MeshServiceCompanion.Companion.IdNotFoundException(id)
            }
            else -> throw MeshServiceCompanion.Companion.InvalidNodeIdException()
        }
    }

    private fun toNodeNum(id: String): Int = when (id) {
        DataPacket.ID_BROADCAST -> DataPacket.NODENUM_BROADCAST
        DataPacket.ID_LOCAL -> myNodeNum
        else -> toNodeInfo(id).num
    }

    /// A helper function that makes it easy to update node info objects
    private fun updateNodeInfo(
        nodeNum: Int,
        withBroadcast: Boolean = true,
        updateFn: (NodeInfo) -> Unit
    ) {
        val info = getOrCreateNodeInfo(nodeNum)
        updateFn(info)

        // This might have been the first time we know an ID for this node, so also update the by ID map
        val userId = info.user?.id.orEmpty()
        if (userId.isNotEmpty())
            nodeDBbyID[userId] = info

        // parcelable is busted
        if (withBroadcast)
            serviceBroadcasts.broadcastNodeChange(info)
    }

    /// Generate a new mesh packet builder with our node as the sender, and the specified node num
    private fun newMeshPacketTo(idNum: Int) = MeshProtos.MeshPacket.newBuilder().apply {
        if (myNodeInfo == null)
            throw RadioNotConnectedException()

        from = myNodeNum

        to = idNum
    }

    /**
     * Generate a new mesh packet builder with our node as the sender, and the specified recipient
     *
     * If id is null we assume a broadcast message
     */
    private fun newMeshPacketTo(id: String) =
        newMeshPacketTo(toNodeNum(id))

    /**
     * Helper to make it easy to build a subpacket in the proper protobufs
     */
    private fun MeshProtos.MeshPacket.Builder.buildMeshPacket(
        wantAck: Boolean = false,
        id: Int = generatePacketId(), // always assign a packet ID if we didn't already have one
        hopLimit: Int = 0,
        priority: MeshProtos.MeshPacket.Priority = MeshProtos.MeshPacket.Priority.UNSET,
        initFn: MeshProtos.Data.Builder.() -> Unit
    ): MeshProtos.MeshPacket {
        this.wantAck = wantAck
        this.id = id
        this.hopLimit = hopLimit
        this.priority = priority
        decoded = MeshProtos.Data.newBuilder().also {
            initFn(it)
        }.build()

        return build()
    }

    /**
     * Helper to make it easy to build a subpacket in the proper protobufs
     */
    private fun MeshProtos.MeshPacket.Builder.buildAdminPacket(
        wantResponse: Boolean = false,
        initFn: AdminProtos.AdminMessage.Builder.() -> Unit
    ): MeshProtos.MeshPacket = buildMeshPacket(
        wantAck = true,
        priority = MeshProtos.MeshPacket.Priority.RELIABLE
    )
    {
        this.wantResponse = wantResponse
        portnumValue = Portnums.PortNum.ADMIN_APP_VALUE
        payload = AdminProtos.AdminMessage.newBuilder().also {
            initFn(it)
        }.build().toByteString()
    }

    /// Generate a DataPacket from a MeshPacket, or null if we didn't have enough data to do so
    private fun toDataPacket(packet: MeshProtos.MeshPacket): DataPacket? {
        return if (!packet.hasDecoded()) {
            // We never convert packets that are not DataPackets
            null
        } else {
            val data = packet.decoded
            val bytes = data.payload.toByteArray()
            val fromId = toNodeID(packet.from)
            val toId = toNodeID(packet.to)
            val hopLimit = packet.hopLimit

            // If the rxTime was not set by the device (because device software was old), guess at a time
            val rxTime = if (packet.rxTime != 0) packet.rxTime else currentSecond()

            when {
                fromId == null -> {
                    errormsg("Ignoring data from ${packet.from} because we don't yet know its ID")
                    null
                }
                toId == null -> {
                    errormsg("Ignoring data to ${packet.to} because we don't yet know its ID")
                    null
                }
                else -> {
                    DataPacket(
                        from = fromId,
                        to = toId,
                        time = rxTime * 1000L,
                        id = packet.id,
                        dataType = data.portnumValue,
                        bytes = bytes,
                        hopLimit = hopLimit
                    )
                }
            }
        }
    }

    private fun toMeshPacket(p: DataPacket): MeshProtos.MeshPacket {
        return newMeshPacketTo(p.to!!).buildMeshPacket(
            id = p.id,
            wantAck = true,
            hopLimit = p.hopLimit
        ) {
            portnumValue = p.dataType
            payload = ByteString.copyFrom(p.bytes)
        }
    }

    override fun rememberDataPacket(p: DataPacket) {
        // Now that we use data packets for more things, we need to be choosier about what we keep.  Since (currently - in the future less so)
        // we only care about old text messages, we just store those...
        if (p.dataType == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE) {
            // discard old messages if needed then add the new one
            while (recentDataPackets.size > 50)
                recentDataPackets.removeAt(0)

            // FIXME - possible kotlin bug in 1.3.72 - it seems that if we start with the (globally shared) emptyList,
            // then adding items are affecting that shared list rather than a copy.   This was causing aliasing of
            // recentDataPackets with messages.value in the GUI.  So if the current list is empty we are careful to make a new list
            if (recentDataPackets.isEmpty())
                recentDataPackets = mutableListOf(p)
            else
                recentDataPackets.add(p)
        }
    }

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun handleReceivedData(packet: MeshProtos.MeshPacket) {
        myNodeInfo?.let { myInfo ->
            val data = packet.decoded
            val bytes = data.payload.toByteArray()
            val fromId = toNodeID(packet.from)
            val dataPacket = toDataPacket(packet)

            if (dataPacket != null) {

                // We ignore most messages that we sent
                val fromUs = myInfo.myNodeNum == packet.from

                debug("Received data from $fromId, portnum=${data.portnum} ${bytes.size} bytes")

                dataPacket.status = MessageStatus.RECEIVED
                rememberDataPacket(dataPacket)

                // if (p.hasUser()) handleReceivedUser(fromNum, p.user)

                /// We tell other apps about most message types, but some may have sensitve data, so that is not shared'
                var shouldBroadcast = !fromUs

                when (data.portnumValue) {
                    Portnums.PortNum.TEXT_MESSAGE_APP_VALUE ->
                        if (!fromUs) {
                            debug("Received CLEAR_TEXT from $fromId")
                            updateMessageNotification(dataPacket)
                        }

                    // Handle new style position info
                    Portnums.PortNum.POSITION_APP_VALUE -> {
                        var u = MeshProtos.Position.parseFrom(data.payload)
                        // position updates from mesh usually don't include times.  So promote rx time
                        if (u.time == 0 && packet.rxTime != 0)
                            u = u.toBuilder().setTime(packet.rxTime).build()
                        // PII
                        // debug("position_app ${packet.from} ${u.toOneLineString()}")
                        handleReceivedPosition(packet.from, u, dataPacket.time)
                    }

                    // Handle new style user info
                    Portnums.PortNum.NODEINFO_APP_VALUE ->
                        if (!fromUs) {
                            val u = MeshProtos.User.parseFrom(data.payload)
                            handleReceivedUser(packet.from, u)
                        }

                    // Handle new style routing info
                    Portnums.PortNum.ROUTING_APP_VALUE -> {
                        shouldBroadcast =
                            true // We always send acks to other apps, because they might care about the messages they sent
                        val u = MeshProtos.Routing.parseFrom(data.payload)
                        if (u.errorReasonValue == MeshProtos.Routing.Error.NONE_VALUE)
                            handleAckNak(true, data.requestId)
                        else
                            handleAckNak(false, data.requestId)
                    }

                    Portnums.PortNum.ADMIN_APP_VALUE -> {
                        val u = AdminProtos.AdminMessage.parseFrom(data.payload)
                        handleReceivedAdmin(packet.from, u)
                        shouldBroadcast = false
                    }

                    else ->
                        debug("No custom processing needed for ${data.portnumValue}")
                }

                // We always tell other apps when new data packets arrive
                if (shouldBroadcast)
                    serviceBroadcasts.broadcastReceivedData(dataPacket)

                GeeksvilleApplication.analytics.track(
                    "num_data_receive",
                    DataPair(1)
                )

                GeeksvilleApplication.analytics.track(
                    "data_receive",
                    DataPair("num_bytes", bytes.size),
                    DataPair("type", data.portnumValue)
                )
            }
        }
    }

    private fun handleReceivedAdmin(fromNodeNum: Int, a: AdminProtos.AdminMessage) {
        // For the time being we only care about admin messages from our local node
        if (fromNodeNum == myNodeNum) {
            when (a.variantCase) {
                AdminProtos.AdminMessage.VariantCase.GET_RADIO_RESPONSE -> {
                    debug("Admin: received radioConfig")
                    radioConfig = a.getRadioResponse
                    requestChannel(0) // Now start reading channels
                }

                AdminProtos.AdminMessage.VariantCase.GET_CHANNEL_RESPONSE -> {
                    val mi = myNodeInfo
                    if (mi != null) {
                        val ch = a.getChannelResponse
                        // add new entries if needed
                        channels[ch.index] = ch
                        debug("Admin: Received channel ${ch.index}")
                        if (ch.index + 1 < mi.maxChannels) {

                            // Stop once we get to the first disabled entry
                            if (/* ch.hasSettings() || */ ch.role != ChannelProtos.Channel.Role.DISABLED) {
                                // Not done yet, request next channel
                                requestChannel(ch.index + 1)
                            } else {
                                debug("We've received the last channel, allowing rest of app to start...")
                                onHasSettings()
                            }
                        } else {
                            debug("Received max channels, starting app")
                            onHasSettings()
                        }
                    }
                }
                else ->
                    warn("No special processing needed for ${a.variantCase}")

            }
        }
    }

    /// Update our DB of users based on someone sending out a User subpacket
    private fun handleReceivedUser(fromNum: Int, p: MeshProtos.User) {
        updateNodeInfo(fromNum) {
            val oldId = it.user?.id.orEmpty()
            it.user = MeshUser(
                if (p.id.isNotEmpty()) p.id else oldId, // If the new update doesn't contain an ID keep our old value
                p.longName,
                p.shortName,
                p.hwModel
            )
        }
    }

    /** Update our DB of users based on someone sending out a Position subpacket
     * @param defaultTime in msecs since 1970
     */
    private fun handleReceivedPosition(
        fromNum: Int,
        p: MeshProtos.Position,
        defaultTime: Long = System.currentTimeMillis()
    ) {
        // Nodes periodically send out position updates, but those updates might not contain a lat & lon (because no GPS lock)
        // We like to look at the local node to see if it has been sending out valid lat/lon, so for the LOCAL node (only)
        // we don't record these nop position updates
        if (myNodeNum == fromNum && p.latitudeI == 0 && p.longitudeI == 0)
            debug("Ignoring nop position update for the local node")
        else
            updateNodeInfo(fromNum) {
                debug("update position: ${it.user?.longName?.toPIIString()} with ${p.toPIIString()}")
                it.position = Position(p, (defaultTime / 1000L).toInt())
            }
    }

    /// If packets arrive before we have our node DB, we delay parsing them until the DB is ready
    // private val earlyReceivedPackets = mutableListOf<MeshPacket>()

    /// If apps try to send packets when our radio is sleeping, we queue them here instead
    private val offlineSentPackets = mutableListOf<DataPacket>()

    /** Keep a record of recently sent packets, so we can properly handle ack/nak */
    private val sentPackets = mutableMapOf<Int, DataPacket>()

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    override fun handleReceivedMeshPacket(packet: MeshProtos.MeshPacket) {
        if (haveNodeDB) {
            processReceivedMeshPacket(packet)
            onNodeDBChanged()
        } else {
            warn("Ignoring early received packet: $packet")
            //earlyReceivedPackets.add(packet)
            //logAssert(earlyReceivedPackets.size < 128) // The max should normally be about 32, but if the device is messed up it might try to send forever
        }
    }

    override fun sendNow(p: DataPacket) {
        val packet = toMeshPacket(p)
        p.status = MessageStatus.ENROUTE
        p.time = System.currentTimeMillis() // update time to the actual time we started sending
        // debug("Sending to radio: ${packet.toPIIString()}")
        sendToRadio(packet)
    }

    private fun processQueuedPackets() {
        offlineSentPackets.forEach { p ->
            // encapsulate our payload in the proper protobufs and fire it off
            sendNow(p)
            serviceBroadcasts.broadcastMessageStatus(p)
        }
        offlineSentPackets.clear()
    }

    /**
     * Change the status on a data packet and update watchers
     */
    private fun changeStatus(p: DataPacket, m: MessageStatus) {
        p.status = m
        serviceBroadcasts.broadcastMessageStatus(p)
    }

    /**
     * Handle an ack/nak packet by updating sent message status
     */
    private fun handleAckNak(isAck: Boolean, id: Int) {
        sentPackets.remove(id)?.let { p ->
            changeStatus(p, if (isAck) MessageStatus.DELIVERED else MessageStatus.ERROR)
        }
    }

    /// Update our model and resend as needed for a MeshPacket we just received from the radio
    private fun processReceivedMeshPacket(packet: MeshProtos.MeshPacket) {
        val fromNum = packet.from

        // FIXME, perhaps we could learn our node ID by looking at any to packets the radio
        // decided to pass through to us (except for broadcast packets)
        //val toNum = packet.to

        // debug("Recieved: $packet")
        if (packet.hasDecoded()) {
            val packetToSave = Packet(
                UUID.randomUUID().toString(),
                "packet",
                System.currentTimeMillis(),
                packet.toString()
            )
            insertPacket(packetToSave)

            // Update last seen for the node that sent the packet, but also for _our node_ because anytime a packet passes
            // through our node on the way to the phone that means that local node is also alive in the mesh

            val isOtherNode = myNodeNum != fromNum
            updateNodeInfo(myNodeNum, withBroadcast = isOtherNode) {
                it.lastHeard = currentSecond()
            }

            // Do not generate redundant broadcasts of node change for this bookkeeping updateNodeInfo call
            // because apps really only care about important updates of node state - which handledReceivedData will give them
            updateNodeInfo(fromNum, withBroadcast = false) {
                // If the rxTime was not set by the device (because device software was old), guess at a time
                val rxTime = if (packet.rxTime != 0) packet.rxTime else currentSecond()

                // Update our last seen based on any valid timestamps.  If the device didn't provide a timestamp make one
                updateNodeInfoTime(it, rxTime)
                it.snr = packet.rxSnr
                it.rssi = packet.rxRssi
            }

            handleReceivedData(packet)
        }
    }

    override fun insertPacket(packetToSave: Packet) {
        serviceScope.handledLaunch {
            // Do not log, because might contain PII
            // info("insert: ${packetToSave.message_type} = ${packetToSave.raw_message.toOneLineString()}")
            packetRepo!!.insert(packetToSave)
        }
    }

    override fun getNewMyNodeInfo(): MyNodeInfo? {
        return newMyNodeInfo
    }

    override fun getNewNodes(): MutableList<MeshProtos.NodeInfo> {
        return newNodes
    }

    private fun currentSecond() = (System.currentTimeMillis() / 1000).toInt()


    /// If we just changed our nodedb, we might want to do somethings
    private fun onNodeDBChanged() {
        maybeUpdateServiceStatusNotification()
    }

    /// Called when we gain/lose connection to our radio
    override fun onConnectionChanged(c: MeshServiceHelper.ConnectionState) {
        debug("onConnectionChanged=$c")

        /// Perform all the steps needed once we start waiting for device sleep to complete
        fun startDeviceSleep() {
            // Just in case the user uncleanly reboots the phone, save now (we normally save in onDestroy)
            saveSettings()

            // lost radio connection, therefore no need to keep listening to GPS
            meshService.stopLocationRequests()

            if (connectTimeMsec != 0L) {
                val now = System.currentTimeMillis()
                connectTimeMsec = 0L

                GeeksvilleApplication.analytics.track(
                    "connected_seconds",
                    DataPair((now - connectTimeMsec) / 1000.0)
                )
            }

            // Have our timeout fire in the approprate number of seconds
            sleepTimeout = serviceScope.handledLaunch {
                try {
                    // If we have a valid timeout, wait that long (+30 seconds) otherwise, just wait 30 seconds
                    val timeout = (radioConfig?.preferences?.lsSecs ?: 0) + 30

                    debug("Waiting for sleeping device, timeout=$timeout secs")
                    delay(timeout * 1000L)
                    warn("Device timeout out, setting disconnected")
                    onConnectionChanged(MeshServiceHelper.ConnectionState.DISCONNECTED)
                } catch (ex: CancellationException) {
                    debug("device sleep timeout cancelled")
                }
            }

            // broadcast an intent with our new connection state
            serviceBroadcasts.broadcastConnection()
        }

        fun startDisconnect() {
            // Just in case the user uncleanly reboots the phone, save now (we normally save in onDestroy)
            saveSettings()

            GeeksvilleApplication.analytics.track(
                "mesh_disconnect",
                DataPair("num_nodes", numNodes),
                DataPair("num_online", numOnlineNodes)
            )
            GeeksvilleApplication.analytics.track("num_nodes", DataPair(numNodes))

            // broadcast an intent with our new connection state
            serviceBroadcasts.broadcastConnection()
        }

        fun startConnect() {
            // Do our startup init
            try {
                connectTimeMsec = System.currentTimeMillis()
                SoftwareUpdateService.sendProgress(
                    meshService,
                    SoftwareUpdateService.ProgressNotStarted,
                    true
                ) // Kinda crufty way of reiniting software update
                startConfig()

            } catch (ex: InvalidProtocolBufferException) {
                errormsg(
                    "Invalid protocol buffer sent by device - update device software and try again",
                    ex
                )
            } catch (ex: RadioNotConnectedException) {
                // note: no need to call startDeviceSleep(), because this exception could only have reached us if it was already called
                errormsg("Lost connection to radio during init - waiting for reconnect")
            } catch (ex: RemoteException) {
                // It seems that when the ESP32 goes offline it can briefly come back for a 100ms ish which
                // causes the phone to try and reconnect.  If we fail downloading our initial radio state we don't want to
                // claim we have a valid connection still
                connectionState = MeshServiceHelper.ConnectionState.DEVICE_SLEEP
                startDeviceSleep()
                throw ex // Important to rethrow so that we don't tell the app all is well
            }
        }

        // Cancel any existing timeouts
        sleepTimeout?.let {
            it.cancel()
            sleepTimeout = null
        }

        connectionState = c
        when (c) {
            MeshServiceHelper.ConnectionState.CONNECTED ->
                startConnect()
            MeshServiceHelper.ConnectionState.DEVICE_SLEEP ->
                startDeviceSleep()
            MeshServiceHelper.ConnectionState.DISCONNECTED ->
                startDisconnect()
        }

        // Update the android notification in the status bar
        maybeUpdateServiceStatusNotification()
    }

    override fun getMyNodeNumber(): Int {
        return numNodes
    }

    override fun getNodeNum(): Int {
        return myNodeNum
    }

    override fun getNumberOnlineNodes(): Int {
        return numOnlineNodes
    }

    override fun getNodeInfo(): MyNodeInfo? {
        return myNodeInfo
    }

    override fun setNodeInfo(nodeInfo: MyNodeInfo?) {
        myNodeInfo = nodeInfo
    }

    override fun getConfigNonce(): Int {
        return configNonce
    }

    override fun addNewNodes() {
        newNodes.forEach(::installNodeInfo)
        newNodes.clear() // Just to save RAM ;-)
        haveNodeDB = true // we now have nodes from real hardware

        regenMyNodeInfo() // we have a node db now, so can possibly find a better hwmodel
        myNodeInfo = newMyNodeInfo // we might have just updated myNodeInfo

        if (deviceVersion < minFirmwareVersion) {
            info("Device firmware is too old, faking config so firmware update can occur")
            onHasSettings()
        } else
            requestRadioConfig()
    }

    override fun setClientPackages(receiverName: String, packageName: String) {
        clientPackages[receiverName] = packageName
    }

    override fun getRecentDataPackets(): MutableList<DataPacket> {
        return recentDataPackets
    }

    override fun getCurrentRegionValue(): Int {
        return curRegionValue
    }

    override fun setCurrentRegionValue(regionCode: Int) {
        curRegionValue = regionCode
    }

    override fun getRadioConfig(): RadioConfigProtos.RadioConfig? {
        return radioConfig
    }

    private fun maybeUpdateServiceStatusNotification() {
        val currentSummary = notificationSummary
        if (previousSummary == null || !previousSummary.equals(currentSummary)) {
            serviceNotifications.updateServiceStateNotification(currentSummary)
            previousSummary = currentSummary
        }
    }

    /**
     * Convert a protobuf NodeInfo into our model objects and update our node DB
     */
    private fun installNodeInfo(info: MeshProtos.NodeInfo) {
        // Just replace/add any entry
        updateNodeInfo(info.num) {
            if (info.hasUser())
                it.user =
                    MeshUser(
                        info.user.id,
                        info.user.longName,
                        info.user.shortName,
                        info.user.hwModel
                    )

            if (info.hasPosition()) {
                // For the local node, it might not be able to update its times because it doesn't have a valid GPS reading yet
                // so if the info is for _our_ node we always assume time is current
                it.position = Position(info.position)
            }

            it.lastHeard = info.lastHeard
        }
    }

    override fun handleNodeInfo(info: MeshProtos.NodeInfo) {
        debug("Received nodeinfo num=${info.num}, hasUser=${info.hasUser()}, hasPosition=${info.hasPosition()}")

        val packetToSave = Packet(
            UUID.randomUUID().toString(),
            "NodeInfo",
            System.currentTimeMillis(),
            info.toString()
        )
        insertPacket(packetToSave)

        logAssert(newNodes.size <= 256) // Sanity check to make sure a device bug can't fill this list forever
        newNodes.add(info)
    }

    override fun getRawMyNodeInfo(): MeshProtos.MyNodeInfo? {
        return rawMyNodeInfo
    }

    private var rawMyNodeInfo: MeshProtos.MyNodeInfo? = null

    /** Regenerate the myNodeInfo model.  We call this twice.  Once after we receive myNodeInfo from the device
     * and again after we have the node DB (which might allow us a better notion of our HwModel.
     */
    private fun regenMyNodeInfo() {
        val myInfo = rawMyNodeInfo
        if (myInfo != null) {
            val a = RadioInterfaceService.getBondedDeviceAddress(meshService)
            val isBluetoothInterface = a != null && a.startsWith("x")

            var hwModelStr = myInfo.hwModelDeprecated
            if (hwModelStr.isEmpty()) {
                val nodeNum =
                    myInfo.myNodeNum // Note: can't use the normal property because myNodeInfo not yet setup
                val ni = nodeDBbyNodeNum[nodeNum] // can't use toNodeInfo because too early
                val asStr = ni?.user?.hwModelString
                if (asStr != null)
                    hwModelStr = asStr
            }
            val mi = with(myInfo) {
                MyNodeInfo(
                    myNodeNum,
                    hasGps,
                    hwModelStr,
                    firmwareVersion,
                    firmwareUpdateFilename != null,
                    isBluetoothInterface && SoftwareUpdateService.shouldUpdate(
                        meshService,
                        DeviceVersion(firmwareVersion)
                    ),
                    currentPacketId.toLong() and 0xffffffffL,
                    if (messageTimeoutMsec == 0) 5 * 60 * 1000 else messageTimeoutMsec, // constants from current device code
                    minAppVersion,
                    maxChannels
                )
            }

            newMyNodeInfo = mi
            setFirmwareUpdateFilename(mi)
        }
    }

    /// If found, the old region string of the form 1.0-EU865 etc...
    private var legacyRegion: String? = null

    /**
     * Update the nodeinfo (called from either new API version or the old one)
     */
    override fun handleMyInfo(myInfo: MeshProtos.MyNodeInfo) {
        val packetToSave = Packet(
            UUID.randomUUID().toString(),
            "MyNodeInfo",
            System.currentTimeMillis(),
            myInfo.toString()
        )
        insertPacket(packetToSave)

        rawMyNodeInfo = myInfo
        legacyRegion = myInfo.region
        regenMyNodeInfo()

        // We'll need to get a new set of channels and settings now
        radioConfig = null

        // prefill the channel array with null channels
        channels = fixupChannelList(listOf<ChannelProtos.Channel>())
    }

    /// scan the channel list and make sure it has one PRIMARY channel and is maxChannels long
    private fun fixupChannelList(lIn: List<ChannelProtos.Channel>): Array<ChannelProtos.Channel> {
        // When updating old firmware, we will briefly be told that there is zero channels
        val maxChannels =
            max(myNodeInfo?.maxChannels ?: 8, 8) // If we don't have my node info, assume 8 channels
        var l = lIn
        while (l.size < maxChannels) {
            val b = ChannelProtos.Channel.newBuilder()
            b.index = l.size
            l += b.build()
        }
        return l.toTypedArray()
    }

    override fun setRegionOnDevice() {
        val curConfigRegion =
            radioConfig?.preferences?.region ?: RadioConfigProtos.RegionCode.Unset

        if (curConfigRegion.number != curRegionValue && curRegionValue != RadioConfigProtos.RegionCode.Unset_VALUE)
            if (deviceVersion >= minFirmwareVersion) {
                info("Telling device to upgrade region")

                // Tell the device to set the new region field (old devices will simply ignore this)
                radioConfig?.let { currentConfig ->
                    val newConfig = currentConfig.toBuilder()

                    val newPrefs = currentConfig.preferences.toBuilder()
                    newPrefs.regionValue = curRegionValue
                    newConfig.preferences = newPrefs.build()

                    sendRadioConfig(newConfig.build())
                }
            } else
                warn("Device is too old to understand region changes")
    }

    /**
     * If we are updating nodes we might need to use old (fixed by firmware build)
     * region info to populate our new universal ROMs.
     *
     * This function updates our saved preferences region info and if the device has an unset new
     * region info, we set it.
     */
    private fun updateRegion() {
        ignoreException {
            // Try to pull our region code from the new preferences field
            // FIXME - do not check net - figuring out why board is rebooting
            val curConfigRegion =
                radioConfig?.preferences?.region ?: RadioConfigProtos.RegionCode.Unset
            if (curConfigRegion != RadioConfigProtos.RegionCode.Unset) {
                info("Using device region $curConfigRegion (code ${curConfigRegion.number})")
                curRegionValue = curConfigRegion.number
            }

            if (curRegionValue == RadioConfigProtos.RegionCode.Unset_VALUE) {
                // look for a legacy region
                val legacyRegex = Regex(".+-(.+)")
                legacyRegion?.let { lr ->
                    val matches = legacyRegex.find(lr)
                    if (matches != null) {
                        val (region) = matches.destructured
                        val newRegion = RadioConfigProtos.RegionCode.valueOf(region)
                        info("Upgrading legacy region $newRegion (code ${newRegion.number})")
                        curRegionValue = newRegion.number
                    }
                }
            }

            // If nothing was set in our (new style radio preferences, but we now have a valid setting - slam it in)
            setRegionOnDevice()
        }
    }

    /// If we've received our initial config, our radio settings and all of our channels, send any queued packets and broadcast connected to clients
    private fun onHasSettings() {

        processQueuedPackets() // send any packets that were queued up

        // broadcast an intent with our new connection state
        serviceBroadcasts.broadcastConnection()
        onNodeDBChanged()
        meshService.reportConnection()

        updateRegion()
    }

    private fun requestRadioConfig() {
        sendToRadio(newMeshPacketTo(myNodeNum).buildAdminPacket(wantResponse = true) {
            getRadioRequest = true
        }, requireConnected = false)
    }

    private fun requestChannel(channelIndex: Int) {
        sendToRadio(newMeshPacketTo(myNodeNum).buildAdminPacket(wantResponse = true) {
            getChannelRequest = channelIndex + 1
        }, requireConnected = false)
    }

    private fun setChannel(channel: ChannelProtos.Channel) {
        sendToRadio(newMeshPacketTo(myNodeNum).buildAdminPacket(wantResponse = true) {
            setChannel = channel
        })
    }

    /**
     * Start the modern (REV2) API configuration flow
     */
    private fun startConfig() {
        configNonce += 1
        newNodes.clear()
        newMyNodeInfo = null
        debug("Starting config nonce=$configNonce")

        sendToRadio(MeshProtos.ToRadio.newBuilder().apply {
            this.wantConfigId = configNonce
        })
    }

    /** Send our current radio config to the device
     */
    private fun sendRadioConfig(c: RadioConfigProtos.RadioConfig) {
        // send the packet into the mesh
        sendToRadio(newMeshPacketTo(myNodeNum).buildAdminPacket {
            setRadio = c
        })

        // Update our cached copy
        radioConfig = c
    }

    /** Set our radio config
     */
    override fun setRadioConfig(payload: ByteArray) {
        val parsed = RadioConfigProtos.RadioConfig.parseFrom(payload)

        sendRadioConfig(parsed)
    }

    override fun getChannelSets(): AppOnlyProtos.ChannelSet {
        return channelSet
    }

    /**
     * Set our owner with either the new or old API
     */
    override fun setOwner(myId: String?, longName: String, shortName: String) {
        val myNode = myNodeInfo
        if (myNode != null) {

            if (longName == localNodeInfo?.user?.longName && shortName == localNodeInfo?.user?.shortName)
                debug("Ignoring nop owner change")
            else {
                debug("SetOwner $myId : ${longName.anonymize} : $shortName")

                val user = MeshProtos.User.newBuilder().also {
                    if (myId != null)  // Only set the id if it was provided
                        it.id = myId
                    it.longName = longName
                    it.shortName = shortName
                }.build()

                // Also update our own map for our nodenum, by handling the packet just like packets from other users

                handleReceivedUser(myNode.myNodeNum, user)

                // encapsulate our payload in the proper protobufs and fire it off
                val packet = newMeshPacketTo(myNodeNum).buildAdminPacket {
                    setOwner = user
                }

                // send the packet into the mesh
                sendToRadio(packet)
            }
        } else
            throw Exception("Can't set user without a node info") // this shouldn't happen
    }

    /// Do not use directly, instead call generatePacketId()
    private var currentPacketId = Random(System.currentTimeMillis()).nextLong().absoluteValue

    /**
     * Generate a unique packet ID (if we know enough to do so - otherwise return 0 so the device will do it)
     */
    @Synchronized
    override fun generatePacketId(): Int {
        val numPacketIds =
            ((1L shl 32) - 1).toLong() // A mask for only the valid packet ID bits, either 255 or maxint

        currentPacketId++

        currentPacketId = currentPacketId and 0xffffffff // keep from exceeding 32 bits

        // Use modulus and +1 to ensure we skip 0 on any values we return
        return ((currentPacketId % numPacketIds) + 1L).toInt()
    }

    var firmwareUpdateFilename: UpdateFilenames? = null

    /***
     * Return the filename we will install on the device
     */
    private fun setFirmwareUpdateFilename(info: MyNodeInfo) {
        firmwareUpdateFilename = try {
            if (info.firmwareVersion != null && info.model != null)
                SoftwareUpdateService.getUpdateFilename(
                    meshService,
                    info.model
                )
            else
                null
        } catch (ex: Exception) {
            errormsg("Unable to update", ex)
            null
        }

        debug("setFirmwareUpdateFilename $firmwareUpdateFilename")
    }

    /// We only allow one update to be running at a time
    private var updateJob: Job? = null

    override fun doFirmwareUpdate() {
        // Run in the IO thread
        val filename = firmwareUpdateFilename ?: throw Exception("No update filename")
        val safe =
            BluetoothInterface.safe
                ?: throw Exception("Can't update - no bluetooth connected")

        if (updateJob?.isActive == true) {
            errormsg("A firmware update is already running")
            throw Exception("Firmware update already running")
        } else {
            debug("Creating firmware update coroutine")
            updateJob = serviceScope.handledLaunch {
                exceptionReporter {
                    debug("Starting firmware update coroutine")
                    SoftwareUpdateService.doUpdate(meshService, safe, filename)
                }
            }
        }
    }

    override fun getMyNodeId(): String? {
        return myNodeID
    }

    /**
     * Remove any sent packets that have been sitting around too long
     *
     * Note: we give each message what the timeout the device code is using, though in the normal
     * case the device will fail after 3 retries much sooner than that (and it will provide a nak to us)
     */
    override fun deleteOldPackets() {
        myNodeInfo?.apply {
            val now = System.currentTimeMillis()

            val old = sentPackets.values.filter { p ->
                (p.status == MessageStatus.ENROUTE && p.time + messageTimeoutMsec < now)
            }

            // Do this using a separate list to prevent concurrent modification exceptions
            old.forEach { p ->
                handleAckNak(false, p.id)
            }
        }
    }

    override fun setSentPackets(p: DataPacket) {
        sentPackets[p.id] = p
    }

    override fun enqueueForSending(p: DataPacket) {
        p.status = MessageStatus.QUEUED
        offlineSentPackets.add(p)
    }

    override fun setChannelSets(parsed: AppOnlyProtos.ChannelSet) {
        channelSet = parsed
    }

    override fun getNodeDBByID(): MutableMap<String, NodeInfo> {
        return nodeDBbyID
    }

    companion object : Logging, MeshServiceCompanion {

        /// Intents broadcast by MeshService

        /* @Deprecated(message = "Does not filter by port number.  For legacy reasons only broadcast for UNKNOWN_APP, switch to ACTION_RECEIVED")
        const val ACTION_RECEIVED_DATA = "$prefix.RECEIVED_DATA" */

//        fun actionReceived(portNum: String) = "$prefix.RECEIVED.$portNum"
//        const val ACTION_NODE_CHANGE = "$prefix.NODE_CHANGE"
//        const val ACTION_MESH_CONNECTED = "$prefix.MESH_CONNECTED"
//        const val ACTION_MESSAGE_STATUS = "$prefix.MESSAGE_STATUS"

//        open class NodeNotFoundException(reason: String) : Exception(reason)
//        class InvalidNodeIdException : NodeNotFoundException("Invalid NodeId")
//        class NodeNumNotFoundException(id: Int) : NodeNotFoundException("NodeNum not found $id")
//        class IdNotFoundException(id: String) : NodeNotFoundException("ID not found $id")
//
//        class NoRadioConfigException(message: String = "No radio settings received (is our app too old?)") :
//            RadioNotConnectedException(message)
//
//        /** We treat software update as similar to loss of comms to the regular bluetooth service (so things like sendPosition for background GPS ignores the problem */
//        class IsUpdatingException :
//            RadioNotConnectedException("Operation prohibited during firmware update")
//
//        /** The minimmum firmware version we know how to talk to. We'll still be able to talk to 1.0 firmwares but only well enough to ask them to firmware update
//         */
//        val minFirmwareVersion = DeviceVersion("1.2.0")
//
//        /**
//         * Talk to our running service and try to set a new device address.  And then immediately
//         * call start on the service to possibly promote our service to be a foreground service.
//         */
//        fun changeDeviceAddress(context: Context, service: IMeshService, address: String?) {
//            service.setDeviceAddress(address)
//            MeshService.Companion.startService(context)
//        }
    }
}
fun updateNodeInfoTime(it: NodeInfo, rxTime: Int) {
    it.lastHeard = rxTime
}