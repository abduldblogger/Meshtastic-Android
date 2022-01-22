package com.geeksville.mesh.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import androidx.annotation.UiThread
import com.geeksville.analytics.DataPair
import com.geeksville.android.GeeksvilleApplication
import com.geeksville.android.Logging
import com.geeksville.android.ServiceClient
import com.geeksville.android.isGooglePlayAvailable
import com.geeksville.mesh.*
import com.geeksville.mesh.MeshProtos.MeshPacket
import com.geeksville.mesh.MeshProtos.ToRadio
import com.geeksville.mesh.android.hasBackgroundPermission
import com.geeksville.mesh.base.helper.MeshServiceHelper
import com.geeksville.mesh.base.helper.MeshServiceHelperImp
import com.geeksville.mesh.common.MeshServiceCompanion
import com.geeksville.mesh.database.PacketRepository
import com.geeksville.mesh.database.entity.Packet
import com.geeksville.util.*
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.protobuf.InvalidProtocolBufferException
import java.util.*

/**
 * Handles all the communication with android apps.  Also keeps an internal model
 * of the network state.
 *
 * Note: this service will go away once all clients are unbound from it.
 * Warning: do not override toString, it causes infinite recursion on some androids (because contextWrapper.getResources calls to string
 */
class MeshService : Service() {
    val meshServiceHelper: MeshServiceHelper = MeshServiceHelperImp(this)

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var packetRepo: PacketRepository? = null

    val radio = ServiceClient {
        IRadioInterfaceService.Stub.asInterface(it).apply {
            // Now that we are connected to the radio service, tell it to connect to the radio
            connect()
        }
    }

    private val locationCallback = MeshServiceLocationCallback(
        ::perhapsSendPosition,
        { meshServiceHelper.onConnectionChanged(MeshServiceHelper.ConnectionState.DEVICE_SLEEP) },
        { meshServiceHelper.getMyNodeNumber() }
    )

    /**
     * a periodic callback that perhaps send our position to other nodes.
     * We first check to see if our local device has already sent a position and if so, we punt until the next check.
     * This allows us to only 'fill in' with GPS positions when the local device happens to have no good GPS sats.
     */
    private fun perhapsSendPosition(
        lat: Double = 0.0,
        lon: Double = 0.0,
        alt: Int = 0,
        destNum: Int = DataPacket.NODENUM_BROADCAST,
        wantResponse: Boolean = false
    ) {
        meshServiceHelper.perhapsSendPosition(
            lat = lat,
            lon = lon,
            alt = alt,
            destNum = destNum,
            wantResponse = wantResponse
        )
    }

    /**
     * start our location requests (if they weren't already running)
     *
     * per https://developer.android.com/training/location/change-location-settings
     */
    @SuppressLint("MissingPermission")
    @UiThread
    private fun startLocationRequests(requestInterval: Long) {
        // FIXME - currently we don't support location reading without google play
        if (fusedLocationClient == null && hasBackgroundPermission() && isGooglePlayAvailable(this)) {
            GeeksvilleApplication.analytics.track("location_start") // Figure out how many users needed to use the phone GPS

            meshServiceHelper.setLocationIntervalMsec(requestInterval)
            val request = LocationRequest.create().apply {
                interval = requestInterval
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            val builder = LocationSettingsRequest.Builder().addLocationRequest(request)
            val locationClient = LocationServices.getSettingsClient(this)
            val locationSettingsResponse = locationClient.checkLocationSettings(builder.build())

            locationSettingsResponse.addOnSuccessListener {
                debug("We are now successfully listening to the GPS")
            }

            locationSettingsResponse.addOnFailureListener { exception ->
                errormsg("Failed to listen to GPS")

                when (exception) {
                    is ResolvableApiException ->
                        exceptionReporter {
                            // Location settings are not satisfied, but this can be fixed
                            // by showing the user a dialog.

                            // Show the dialog by calling startResolutionForResult(),
                            // and check the result in onActivityResult().
                            // exception.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS)

                            // For now just punt and show a dialog
                            meshServiceHelper.warnUserAboutLocation()
                        }
                    is ApiException ->
                        when (exception.statusCode) {
                            17 ->
                                // error: cancelled by user
                                errormsg("User cancelled location access", exception)
                            8502 ->
                                // error: settings change unavailable
                                errormsg(
                                    "Settings-change-unavailable, user disabled location access (globally?)",
                                    exception
                                )
                            else ->
                                Exceptions.report(exception)
                        }
                    else ->
                        Exceptions.report(exception)
                }
            }

            val client = LocationServices.getFusedLocationProviderClient(this)

            // FIXME - should we use Looper.myLooper() in the third param per https://github.com/android/location-samples/blob/432d3b72b8c058f220416958b444274ddd186abd/LocationUpdatesForegroundService/app/src/main/java/com/google/android/gms/location/sample/locationupdatesforegroundservice/LocationUpdatesService.java
            client.requestLocationUpdates(request, locationCallback, null)

            fusedLocationClient = client
        }

    }

    fun stopLocationRequests() {
        if (fusedLocationClient != null) {
            debug("Stopping location requests")
            GeeksvilleApplication.analytics.track("location_stop")
            fusedLocationClient?.removeLocationUpdates(locationCallback)
            fusedLocationClient = null
        }
    }

    /// Safely access the radio service, if not connected an exception will be thrown
    private val connectedRadio: IRadioInterfaceService =
        meshServiceHelper.getRadioInterfaceService(radio)

    fun getConnectionRadio(): IRadioInterfaceService {
        return connectedRadio
    }

    /**
     * Send a mesh packet to the radio, if the radio is not currently connected this function will throw NotConnectedException
     */
    private fun sendToRadio(packet: MeshPacket, requireConnected: Boolean = true) {
        meshServiceHelper.sendToRadio(ToRadio.newBuilder().apply {
            this.packet = packet
        }, requireConnected)
    }

    override fun onCreate() {
        super.onCreate()

        info("Creating mesh service")
        packetRepo = meshServiceHelper.getPacketRepo()

        // Switch to the IO thread
        meshServiceHelper.handleLaunch()
    }

    /**
     * If someone binds to us, this will be called after on create
     */
    override fun onBind(intent: Intent?): IBinder? {
        meshServiceHelper.startForeground()

        return binder
    }

    /**
     * If someone starts us (or restarts us) this will be called after onCreate)
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        meshServiceHelper.startForeground()

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        info("Destroying mesh service")

        // This might fail if we get destroyed before the handledLaunch completes
        ignoreException(silent = true) {
            unregisterReceiver(radioInterfaceReceiver)
        }

        radio.close()
        meshServiceHelper.saveSettings()

        stopForeground(true) // Make sure we aren't using the notification first
        meshServiceHelper.serviceNotificationsClose()

        super.onDestroy()
        meshServiceHelper.cancelServiceJob()
    }

    private fun setupLocationRequests() {
        stopLocationRequests()
        val mi = meshServiceHelper.getNodeInfo()
        val prefs = meshServiceHelper.getRadioConfig()?.preferences
        if (mi != null && prefs != null) {
            val broadcastSecs = prefs.positionBroadcastSecs

            var desiredInterval = if (broadcastSecs == 0) // unset by device, use default
                15 * 60 * 1000L
            else
                broadcastSecs * 1000L

            if (prefs.locationShare == RadioConfigProtos.LocationSharing.LocDisabled) {
                info("GPS location sharing is disabled")
                desiredInterval = 0
            }

            if (prefs.fixedPosition) {
                info("Node has fixed position, therefore not overriding position")
                desiredInterval = 0
            }

            if (desiredInterval != 0L) {
                info("desired GPS assistance interval $desiredInterval")
                startLocationRequests(desiredInterval)
            } else {
                info("No GPS assistance desired, but sending UTC time to mesh")
                meshServiceHelper.sendPosition()
            }
        }
    }

    /**
     * Send in analytics about mesh connection
     */
    fun reportConnection() {
        val radioModel =
            DataPair("radio_model", meshServiceHelper.getNodeInfo()?.model ?: "unknown")
        GeeksvilleApplication.analytics.track(
            "mesh_connect",
            DataPair("num_nodes", meshServiceHelper.getNodeNum()),
            DataPair("num_online", meshServiceHelper.getNumberOnlineNodes()),
            radioModel
        )

        // Once someone connects to hardware start tracking the approximate number of nodes in their mesh
        // this allows us to collect stats on what typical mesh size is and to tell difference between users who just
        // downloaded the app, vs has connected it to some hardware.
        GeeksvilleApplication.analytics.setUserInfo(
            DataPair("num_nodes", meshServiceHelper.getMyNodeNumber()),
            radioModel
        )
    }

    /**
     * Receives messages from our BT radio service and processes them to update our model
     * and send to clients as needed.
     */
    private val radioInterfaceReceiver = object : BroadcastReceiver() {

        // Important to never throw exceptions out of onReceive
        override fun onReceive(context: Context, intent: Intent) = exceptionReporter {
            // NOTE: Do not call handledLaunch here, because it can cause out of order message processing - because each routine is scheduled independently
            // serviceScope.handledLaunch {
            debug("Received broadcast ${intent.action}")
            when (intent.action) {
                RadioInterfaceService.RADIO_CONNECTED_ACTION -> {
                    try {
                        val connected = intent.getBooleanExtra(EXTRA_CONNECTED, false)
                        val permanent = intent.getBooleanExtra(EXTRA_PERMANENT, false)
                        meshServiceHelper.onConnectionChanged(
                            when {
                                connected -> MeshServiceHelper.ConnectionState.CONNECTED
                                permanent -> MeshServiceHelper.ConnectionState.DISCONNECTED
                                else -> MeshServiceHelper.ConnectionState.DEVICE_SLEEP
                            }
                        )
                    } catch (ex: RemoteException) {
                        // This can happen sometimes (especially if the device is slowly dying due to killing power, don't report to crashlytics
                        warn("Abandoning reconnect attempt, due to errors during init: ${ex.message}")
                    }
                }

                RadioInterfaceService.RECEIVE_FROMRADIO_ACTION -> {
                    val bytes = intent.getByteArrayExtra(EXTRA_PAYLOAD)!!
                    try {
                        val proto =
                            MeshProtos.FromRadio.parseFrom(bytes)
                        // info("Received from radio service: ${proto.toOneLineString()}")
                        when (proto.payloadVariantCase.number) {
                            MeshProtos.FromRadio.PACKET_FIELD_NUMBER -> meshServiceHelper.handleReceivedMeshPacket(
                                proto.packet
                            )

                            MeshProtos.FromRadio.CONFIG_COMPLETE_ID_FIELD_NUMBER -> handleConfigComplete(
                                proto.configCompleteId
                            )

                            MeshProtos.FromRadio.MY_INFO_FIELD_NUMBER -> meshServiceHelper.handleMyInfo(
                                proto.myInfo
                            )

                            MeshProtos.FromRadio.NODE_INFO_FIELD_NUMBER -> meshServiceHelper.handleNodeInfo(
                                proto.nodeInfo
                            )

                            // MeshProtos.FromRadio.RADIO_FIELD_NUMBER -> handleRadioConfig(proto.radio)

                            else -> errormsg("Unexpected FromRadio variant")
                        }
                    } catch (ex: InvalidProtocolBufferException) {
                        errormsg("Invalid Protobuf from radio, len=${bytes.size}", ex)
                    }
                }

                else -> errormsg("Unexpected radio interface broadcast")
            }
        }
    }


    private fun sendAnalytics() {
        val myInfo = meshServiceHelper.getRawMyNodeInfo()
        val mi = meshServiceHelper.getNodeInfo()
        if (myInfo != null && mi != null) {
            /// Track types of devices and firmware versions in use
            GeeksvilleApplication.analytics.setUserInfo(
                // DataPair("region", mi.region),
                DataPair("firmware", mi.firmwareVersion),
                DataPair("has_gps", mi.hasGPS),
                DataPair("hw_model", mi.model),
                DataPair("dev_error_count", myInfo.errorCount)
            )

            if (myInfo.errorCode != MeshProtos.CriticalErrorCode.Unspecified && myInfo.errorCode != MeshProtos.CriticalErrorCode.None) {
                GeeksvilleApplication.analytics.track(
                    "dev_error",
                    DataPair("code", myInfo.errorCode.number),
                    DataPair("address", myInfo.errorAddress),

                    // We also include this info, because it is required to correctly decode address from the map file
                    DataPair("firmware", mi.firmwareVersion),
                    DataPair("hw_model", mi.model)
                    // DataPair("region", mi.region)
                )
            }
        }
    }

    private fun handleConfigComplete(configCompleteId: Int) {
        if (configCompleteId == meshServiceHelper.getConfigNonce()) {

            val packetToSave = Packet(
                UUID.randomUUID().toString(),
                "ConfigComplete",
                System.currentTimeMillis(),
                configCompleteId.toString()
            )
            meshServiceHelper.insertPacket(packetToSave)

            // This was our config request
            if (meshServiceHelper.getNewMyNodeInfo() == null
                || meshServiceHelper.getNewNodes().isEmpty()
            )
                errormsg("Did not receive a valid config")
            else {
                meshServiceHelper.discardNodeDB()
                debug("Installing new node DB")
                meshServiceHelper.setNodeInfo(meshServiceHelper.getNewMyNodeInfo()) // Install myNodeInfo as current
                meshServiceHelper.addNewNodes()
                sendAnalytics()
            }
        } else
            warn("Ignoring stale config complete")
    }


    private val binder = object : IMeshService.Stub() {

        override fun setDeviceAddress(deviceAddr: String?) = toRemoteExceptions {
            debug("Passing through device change to radio service: ${deviceAddr.anonymize}")

            val res = radio.service.setDeviceAddress(deviceAddr)
            if (res) {
                meshServiceHelper.discardNodeDB()
            }
            res
        }

        // Note: bound methods don't get properly exception caught/logged, so do that with a wrapper
        // per https://blog.classycode.com/dealing-with-exceptions-in-aidl-9ba904c6d63
        override fun subscribeReceiver(packageName: String, receiverName: String) =
            toRemoteExceptions {
                meshServiceHelper.setClientPackages(receiverName, packageName)
            }

        override fun getOldMessages(): MutableList<DataPacket> {
            return meshServiceHelper.getRecentDataPackets()
        }

        override fun getUpdateStatus(): Int = SoftwareUpdateService.progress
        override fun getRegion(): Int = meshServiceHelper.getCurrentRegionValue()

        override fun setRegion(regionCode: Int) = toRemoteExceptions {
            meshServiceHelper.setCurrentRegionValue(regionCode)
            meshServiceHelper.setRegionOnDevice()
        }

        override fun startFirmwareUpdate() = toRemoteExceptions {
            meshServiceHelper.doFirmwareUpdate()
        }

        override fun getMyNodeInfo(): MyNodeInfo? = meshServiceHelper.getNodeInfo()

        override fun getMyId() = toRemoteExceptions { meshServiceHelper.getMyNodeId() }

        override fun setOwner(myId: String?, longName: String, shortName: String) =
            toRemoteExceptions {
                meshServiceHelper.setOwner(myId, longName, shortName)
            }

        override fun send(p: DataPacket) {
            toRemoteExceptions {
                // Init from and id
                meshServiceHelper.getMyNodeId()?.let { myId ->
                    // we no longer set from, we let the device do it
                    //if (p.from == DataPacket.ID_LOCAL)
                    //    p.from = myId

                    if (p.id == 0)
                        p.id = meshServiceHelper.generatePacketId()
                }

                info("sendData dest=${p.to}, id=${p.id} <- ${p.bytes!!.size} bytes (connectionState=${meshServiceHelper.getConnectionState()})")

                if (p.dataType == 0)
                    throw Exception("Port numbers must be non-zero!") // we are now more strict

                // Keep a record of datapackets, so GUIs can show proper chat history
                meshServiceHelper.rememberDataPacket(p)

                if (p.bytes.size >= MeshProtos.Constants.DATA_PAYLOAD_LEN.number) {
                    p.status = MessageStatus.ERROR
                    throw RemoteException("Message too long")
                }

                if (p.id != 0) { // If we have an ID we can wait for an ack or nak
                    meshServiceHelper.deleteOldPackets()
                    meshServiceHelper.setSentPackets(p)
                }

                // If radio is sleeping or disconnected, queue the packet
                when (meshServiceHelper.getConnectionState()) {
                    MeshServiceHelper.ConnectionState.CONNECTED ->
                        try {
                            meshServiceHelper.sendNow(p)
                        } catch (ex: Exception) {
                            // This can happen if a user is unlucky and the device goes to sleep after the GUI starts a send, but before we update connectionState
                            errormsg("Error sending message, so enqueueing", ex)
                            meshServiceHelper.enqueueForSending(p)
                        }
                    else -> // sleeping or disconnected
                        meshServiceHelper.enqueueForSending(p)
                }

                GeeksvilleApplication.analytics.track(
                    "data_send",
                    DataPair("num_bytes", p.bytes.size),
                    DataPair("type", p.dataType)
                )

                GeeksvilleApplication.analytics.track(
                    "num_data_sent",
                    DataPair(1)
                )
            }
        }

        override fun getRadioConfig(): ByteArray = toRemoteExceptions {
            meshServiceHelper.getRadioConfig()?.toByteArray()
                ?: throw MeshServiceCompanion.Companion.NoRadioConfigException()
        }

        override fun setRadioConfig(payload: ByteArray) = toRemoteExceptions {
            meshServiceHelper.setRadioConfig(payload)
        }

        override fun getChannels(): ByteArray = toRemoteExceptions {
            meshServiceHelper.getChannelSets().toByteArray()
        }

        override fun setChannels(payload: ByteArray?) = toRemoteExceptions {
            val parsed = AppOnlyProtos.ChannelSet.parseFrom(payload)
            meshServiceHelper.setChannelSets(parsed)
        }

        override fun getNodes(): MutableList<NodeInfo> = toRemoteExceptions {
            val r = meshServiceHelper.getNodeDBByID().values.toMutableList()
            info("in getOnline, count=${r.size}")
            // return arrayOf("+16508675309")
            r
        }

        override fun connectionState(): String = toRemoteExceptions {
            val r = meshServiceHelper.getConnectionState()
            info("in connectionState=$r")
            r.toString()
        }

        override fun setupProvideLocation() = toRemoteExceptions {
            setupLocationRequests()
        }

        override fun stopProvideLocation() = toRemoteExceptions {
            stopLocationRequests()
        }

    }

//    fun startMeshService(context: Context) {
//        startService(context)
//    }

    fun getRadioInterfaceReceiver(): BroadcastReceiver {
        return radioInterfaceReceiver
    }

    companion object : Logging
}

fun updateNodeInfoTime(it: NodeInfo, rxTime: Int) {
    it.lastHeard = rxTime
}
