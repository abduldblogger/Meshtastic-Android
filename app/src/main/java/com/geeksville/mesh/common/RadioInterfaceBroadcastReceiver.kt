package com.geeksville.mesh.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.RemoteException
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.base.helper.MeshServiceHelper
import com.geeksville.mesh.database.entity.Packet
import com.geeksville.mesh.service.*
import com.geeksville.util.exceptionReporter
import com.google.protobuf.InvalidProtocolBufferException
import java.util.*

class RadioInterfaceBroadcastReceiver(
    private val meshServiceHelper: MeshServiceHelper,
    private val meshService: MeshService
) :
    BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = exceptionReporter {
        // NOTE: Do not call handledLaunch here, because it can cause out of order message processing - because each routine is scheduled independently
        // serviceScope.handledLaunch {
        MeshService.debug("Received broadcast ${intent.action}")
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
                    MeshService.warn("Abandoning reconnect attempt, due to errors during init: ${ex.message}")
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

                        else -> MeshService.errormsg("Unexpected FromRadio variant")
                    }
                } catch (ex: InvalidProtocolBufferException) {
                    MeshService.errormsg("Invalid Protobuf from radio, len=${bytes.size}", ex)
                }
            }

            else -> MeshService.errormsg("Unexpected radio interface broadcast")
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
                MeshService.errormsg("Did not receive a valid config")
            else {
                meshServiceHelper.discardNodeDB()
                MeshService.debug("Installing new node DB")
                meshServiceHelper.setNodeInfo(meshServiceHelper.getNewMyNodeInfo()) // Install myNodeInfo as current
                meshServiceHelper.addNewNodes()
                meshService.sendAnalytics()
            }
        } else
            MeshService.warn("Ignoring stale config complete")
    }
}