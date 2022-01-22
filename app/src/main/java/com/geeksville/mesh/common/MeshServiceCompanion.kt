package com.geeksville.mesh.common

import android.content.Context
import com.geeksville.mesh.IMeshService
import com.geeksville.mesh.Portnums
import com.geeksville.mesh.model.DeviceVersion
import com.geeksville.mesh.service.MeshService
import com.geeksville.mesh.service.RadioNotConnectedException
import com.geeksville.mesh.service.prefix
import com.geeksville.mesh.service.startService

interface MeshServiceCompanion {
    val ACTION_NODE_CHANGE: String
        get() = "$prefix.NODE_CHANGE"
    val ACTION_MESH_CONNECTED: String
        get() = "$prefix.MESH_CONNECTED"
    val ACTION_MESSAGE_STATUS: String
        get() = "$prefix.MESSAGE_STATUS"

    fun actionReceived(portNum: Int): String {
        val portType = Portnums.PortNum.forNumber(portNum)
        val portStr = portType?.toString() ?: portNum.toString()

        return actionReceived(portStr)
    }

    fun actionReceived(portNum: String) = "$prefix.RECEIVED.$portNum"

    companion object {
        /// generate a RECEIVED action filter string that includes either the portnumber as an int, or preferably a symbolic name from portnums.proto

        /// Intents broadcast by MeshService

        /* @Deprecated(message = "Does not filter by port number.  For legacy reasons only broadcast for UNKNOWN_APP, switch to ACTION_RECEIVED")
        const val ACTION_RECEIVED_DATA = "$prefix.RECEIVED_DATA" */


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

        /**
         * Talk to our running service and try to set a new device address.  And then immediately
         * call start on the service to possibly promote our service to be a foreground service.
         */
        fun changeDeviceAddress(context: Context, service: IMeshService, address: String?) {
            service.setDeviceAddress(address)
            MeshService.Companion.startService(context)
        }
    }
}
