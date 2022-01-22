package com.geeksville.mesh.common

import android.content.Intent
import com.geeksville.mesh.Portnums
import com.geeksville.mesh.base.helper.MeshServiceHelperImp

object IntentUtil {
    fun getMeshIntent(): Intent {
        return Intent().setClassName(
            "com.geeksville.mesh",
            "com.geeksville.mesh.service.MeshService"
        )
    }

    fun getBroadCastIntent(portNum: Int): String {
        val portType = Portnums.PortNum.forNumber(portNum)
        val portStr = portType?.toString() ?: portNum.toString()

        return MeshServiceHelperImp.actionReceived(portStr)
    }
}