package com.geeksville.mesh.base.helper

import android.content.Intent
import com.geeksville.android.ServiceClient
import com.geeksville.mesh.*
import com.geeksville.mesh.database.PacketRepository
import com.geeksville.mesh.database.entity.Packet

interface MeshServiceHelper {
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTED,
        DEVICE_SLEEP // device is in LS sleep state, it will reconnected to us over bluetooth once it has data
    }

    fun createIntent(): Intent
    fun perhapsSendPosition(
        lat: Double = 0.0,
        lon: Double = 0.0,
        alt: Int = 0,
        destNum: Int = DataPacket.NODENUM_BROADCAST,
        wantResponse: Boolean = false
    )

    fun setLocationIntervalMsec(locationInterval: Long)
    fun warnUserAboutLocation()
    fun getConnectionState(): ConnectionState
    fun getRadioInterfaceService(radioInterfaceService: ServiceClient<IRadioInterfaceService>): IRadioInterfaceService
    fun sendToRadio(p: MeshProtos.ToRadio.Builder, requireConnected: Boolean = true)
    fun updateMessageNotification(dataPacket: DataPacket)
    fun startForeground()
    fun getPacketRepo(): PacketRepository
    fun handleLaunch()
    fun serviceNotificationsClose()
    fun cancelServiceJob()
    fun saveSettings()
    fun onConnectionChanged(deviceSleep: MeshServiceHelper.ConnectionState)
    fun getMyNodeNumber(): Int
    fun getNodeInfo(): MyNodeInfo?
    fun getRadioConfig(): RadioConfigProtos.RadioConfig?
    fun sendPosition(
        lat: Double = 0.0,
        lon: Double = 0.0,
        alt: Int = 0,
        destNum: Int = DataPacket.NODENUM_BROADCAST,
        wantResponse: Boolean = false
    )

    fun getNodeNum(): Int
    fun getNumberOnlineNodes(): Int
    fun handleReceivedMeshPacket(packet: MeshProtos.MeshPacket)
    fun handleMyInfo(myInfo: MeshProtos.MyNodeInfo)
    fun handleNodeInfo(info: MeshProtos.NodeInfo)
    fun getRawMyNodeInfo(): MeshProtos.MyNodeInfo?
    fun insertPacket(packetToSave: Packet)
    fun getNewMyNodeInfo(): MyNodeInfo?
    fun getNewNodes(): MutableList<MeshProtos.NodeInfo>
    fun discardNodeDB()
    fun setNodeInfo(newMyNodeInfo: MyNodeInfo?)
    fun getConfigNonce(): Int
    fun addNewNodes()
    fun setClientPackages(receiverName: String, packageName: String)
    fun getRecentDataPackets(): MutableList<DataPacket>
    fun getCurrentRegionValue(): Int
    fun setCurrentRegionValue(regionCode: Int)
    fun setRegionOnDevice()
    fun doFirmwareUpdate()
    fun getMyNodeId(): String?
    fun setOwner(myId: String?, longName: String, shortName: String)
    fun generatePacketId(): Int
    fun rememberDataPacket(p: DataPacket)
    fun deleteOldPackets()
    fun setSentPackets(p: DataPacket)
    fun setRadioConfig(payload: ByteArray)
    fun getChannelSets(): AppOnlyProtos.ChannelSet
    fun sendNow(p: DataPacket)
    fun enqueueForSending(p: DataPacket)
    fun setChannelSets(parsed: AppOnlyProtos.ChannelSet)
    fun getNodeDBByID(): MutableMap<String, NodeInfo>
}