package com.geeksville.mesh.base.helper

import com.geeksville.android.AppPrefs

interface MeshUtilApplicationHelper {
    fun initFirebaseCrashlytics(isAnalyticsAllowed: Boolean, pref: AppPrefs)
    fun crashlyticsLog(log: String)
    fun sendCrashReports()
    fun attachExceptionsReporter()
}