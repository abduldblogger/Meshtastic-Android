package com.geeksville.mesh.base.helper

import com.geeksville.android.AppPrefs
import com.geeksville.mesh.BuildConfig
import com.geeksville.util.Exceptions
import com.google.firebase.crashlytics.FirebaseCrashlytics

class MeshUtilApplicationHelperImp : MeshUtilApplicationHelper {
    private lateinit var crashlytics: FirebaseCrashlytics
    private var isAnalyticsAllowed = false
    override fun initFirebaseCrashlytics(isAnalyticsAllowed: Boolean, pref: AppPrefs) {
        this.isAnalyticsAllowed = isAnalyticsAllowed
        crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(isAnalyticsAllowed)
        crashlytics.setCustomKey("debug_build", BuildConfig.DEBUG)
        crashlytics.setUserId(pref.getInstallId()) // be able to group all bugs per anonymous user

    }

    override fun crashlyticsLog(log: String) {
        if (::crashlytics.isInitialized)
            crashlytics.log(log)
    }

    override fun sendCrashReports() {
        if (isAnalyticsAllowed)
            crashlytics.sendUnsentReports()
    }

    override fun attachExceptionsReporter() {
        Exceptions.reporter = { exception, _, _ ->
            crashlytics.recordException(exception)
            sendCrashReports() // Send the new report
        }
    }
}