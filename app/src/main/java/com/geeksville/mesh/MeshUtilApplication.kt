package com.geeksville.mesh

import android.os.Debug
import com.geeksville.android.AppPrefs
import com.geeksville.android.BuildUtils.isEmulator
import com.geeksville.android.GeeksvilleApplication
import com.geeksville.android.Logging
import com.geeksville.mesh.base.helper.MeshUtilApplicationHelper
import com.geeksville.mesh.base.helper.MeshUtilApplicationHelperImp
import com.mapbox.mapboxsdk.Mapbox


class MeshUtilApplication : GeeksvilleApplication() {

    override fun onCreate() {
        super.onCreate()
        val meshUtilApplicationHelper: MeshUtilApplicationHelper = MeshUtilApplicationHelperImp()

        Logging.showLogs = BuildConfig.DEBUG

        // We default to off in the manifest - we turn on here if the user approves
        // leave off when running in the debugger
        if (!isEmulator && (!BuildConfig.DEBUG || !Debug.isDebuggerConnected())) {
            val pref = AppPrefs(this)
            meshUtilApplicationHelper.initFirebaseCrashlytics(isAnalyticsAllowed, pref)
            // We always send our log messages to the crashlytics lib, but they only get sent to the server if we report an exception
            // This makes log messages work properly if someone turns on analytics just before they click report bug.
            // send all log messages through crashyltics, so if we do crash we'll have those in the report
            val standardLogger = Logging.printlog
            Logging.printlog = { level, tag, message ->
                meshUtilApplicationHelper.crashlyticsLog("$tag: $message")
                standardLogger(level, tag, message)
            }

            fun sendCrashReports() {
                meshUtilApplicationHelper.sendCrashReports()
            }

            // Send any old reports if user approves
            sendCrashReports()

            // Attach to our exception wrapper
            meshUtilApplicationHelper.attachExceptionsReporter()
        }

        Mapbox.getInstance(this, getString(R.string.mapbox_access_token))
    }
}