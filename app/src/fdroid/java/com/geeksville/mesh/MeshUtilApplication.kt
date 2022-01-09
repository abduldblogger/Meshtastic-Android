package com.geeksville.mesh

import android.os.Debug
import com.geeksville.android.AppPrefs
import com.geeksville.android.BuildUtils.isEmulator
import com.geeksville.android.GeeksvilleApplication
import com.geeksville.android.Logging
import com.geeksville.util.Exceptions
import com.mapbox.mapboxsdk.Mapbox

class MeshUtilApplication : GeeksvilleApplication() {

    override fun onCreate() {
        super.onCreate()

        Logging.showLogs = BuildConfig.DEBUG

        // We default to off in the manifest - we turn on here if the user approves
        // leave off when running in the debugger
        if (!isEmulator && (!BuildConfig.DEBUG || !Debug.isDebuggerConnected())) {
            val pref = AppPrefs(this)
            val standardLogger = Logging.printlog
            Logging.printlog = { level, tag, message ->
                standardLogger(level, tag, message)
            }

        }

        Mapbox.getInstance(this, getString(R.string.mapbox_access_token))
    }
}
