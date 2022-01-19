package com.geeksville.mesh.base.helper

import android.content.Context
import android.content.Intent
import com.geeksville.android.ServiceClient
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.IMeshService
import com.geeksville.mesh.IRadioInterfaceService
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.database.PacketRepository
import com.geeksville.mesh.service.SendPosition

interface MeshServiceHelper {
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTED,
        DEVICE_SLEEP // device is in LS sleep state, it will reconnected to us over bluetooth once it has data
    }

    fun createIntent(): Intent
    fun changeDeviceAddress(context: Context, service: IMeshService, address: String?)
    fun actionReceived(portNum: Int): String
    fun perhapsSendPosition(
        lat: Double = 0.0,
        lon: Double = 0.0,
        alt: Int = 0,
        destNum: Int = DataPacket.NODENUM_BROADCAST,
        wantResponse: Boolean = false
    ): SendPosition

    fun setLocationIntervalMsec(locationInterval: Long)
    fun warnUserAboutLocation()
    fun getConnectionState(): ConnectionState
    fun getRadioInterfaceService(radioInterfaceService: ServiceClient<IRadioInterfaceService>): IRadioInterfaceService
    fun sendToRadio(p: MeshProtos.ToRadio.Builder, requireConnected: Boolean = true)
    fun updateMessageNotification(dataPacket: DataPacket)
    fun startForeground()
    fun getPacketRepo(): PacketRepository
    fun handleLaunch()
}