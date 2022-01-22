package com.geeksville.mesh.common

import android.os.RemoteException
import com.geeksville.mesh.*
import com.geeksville.mesh.base.helper.MeshServiceHelper
import com.geeksville.mesh.service.MeshService
import com.geeksville.mesh.service.SoftwareUpdateService
import com.geeksville.util.anonymize
import com.geeksville.util.toRemoteExceptions

class MeshServiceBinder(
    private val meshServiceHelper: MeshServiceHelper,
    private val meshService: MeshService
) : IMeshService.Stub() {
    override fun setDeviceAddress(deviceAddr: String?) = toRemoteExceptions {
        MeshService.debug("Passing through device change to radio service: ${deviceAddr.anonymize}")

        val res = meshService.getRadioServiceClient().service.setDeviceAddress(deviceAddr)
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
            MeshService.info("sendData dest=${p.to}, id=${p.id} <- ${p.bytes!!.size} bytes (connectionState=${meshServiceHelper.getConnectionState()})")

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
                        MeshService.errormsg("Error sending message, so enqueueing", ex)
                        meshServiceHelper.enqueueForSending(p)
                    }
                else -> // sleeping or disconnected
                    meshServiceHelper.enqueueForSending(p)
            }

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
        MeshService.info("in getOnline, count=${r.size}")
        // return arrayOf("+16508675309")
        r
    }

    override fun connectionState(): String = toRemoteExceptions {
        val r = meshServiceHelper.getConnectionState()
        MeshService.info("in connectionState=$r")
        r.toString()
    }

    override fun setupProvideLocation() = toRemoteExceptions {
        meshService.setupLocationRequests()
    }

    override fun stopProvideLocation() = toRemoteExceptions {
        meshService.stopLocationRequests()
    }

}