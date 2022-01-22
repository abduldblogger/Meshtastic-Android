package com.geeksville.mesh.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.IBinder
import androidx.annotation.UiThread
import com.geeksville.android.Logging
import com.geeksville.android.ServiceClient
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.IRadioInterfaceService
import com.geeksville.mesh.RadioConfigProtos
import com.geeksville.mesh.base.helper.MeshServiceHelper
import com.geeksville.mesh.base.helper.MeshServiceHelperImp
import com.geeksville.mesh.common.MeshServiceBinder
import com.geeksville.mesh.common.RadioInterfaceBroadcastReceiver
import com.geeksville.mesh.database.PacketRepository
import com.geeksville.util.ignoreException

/**
 * Handles all the communication with android apps.  Also keeps an internal model
 * of the network state.
 *
 * Note: this service will go away once all clients are unbound from it.
 * Warning: do not override toString, it causes infinite recursion on some androids (because contextWrapper.getResources calls to string
 */
class MeshService : Service() {
    val meshServiceHelper: MeshServiceHelper = MeshServiceHelperImp(this)
    private var packetRepo: PacketRepository? = null

    val radio = ServiceClient {
        IRadioInterfaceService.Stub.asInterface(it).apply {
            // Now that we are connected to the radio service, tell it to connect to the radio
            connect()
        }
    }

    fun getRadioServiceClient()
            : ServiceClient<IRadioInterfaceService> {
        return radio
    }

    private var locationIntervalMsec = 0L

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
    }

    fun stopLocationRequests() {
    }

    /// Safely access the radio service, if not connected an exception will be thrown
    private val connectedRadio: IRadioInterfaceService
        get() = (if (meshServiceHelper.getConnectionState() == MeshServiceHelper.ConnectionState.CONNECTED) radio.serviceP else null)
            ?: throw RadioNotConnectedException()

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

    fun setupLocationRequests() {
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
    }

    /**
     * Receives messages from our BT radio service and processes them to update our model
     * and send to clients as needed.
     */
    private val radioInterfaceReceiver = RadioInterfaceBroadcastReceiver(meshServiceHelper, this)

    fun sendAnalytics() {
    }

    fun getRadioInterfaceReceiver(): BroadcastReceiver? {
        return radioInterfaceReceiver
    }

    fun getConnectionRadio(): IRadioInterfaceService {
        return connectedRadio
    }

    private val binder = MeshServiceBinder(meshServiceHelper, this)

    companion object : Logging
}