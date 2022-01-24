package com.geeksville.mesh.base.helper

import android.content.Intent
import com.geeksville.mesh.MainActivity
import com.geeksville.util.exceptionReporter
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.vorlonsoft.android.rate.AppRate
import com.vorlonsoft.android.rate.StoreType
import com.google.android.gms.auth.api.signin.GoogleSignIn

class MainActivityHelperImp() : MainActivityHelper {
    override fun askToRate(context: MainActivity) {
        exceptionReporter { // Got one IllegalArgumentException from inside this lib, but we don't want to crash our app because of bugs in this optional feature

            val hasGooglePlay = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) != ConnectionResult.SERVICE_MISSING

            val rater = AppRate.with(context)
                .setInstallDays(10.toByte()) // default is 10, 0 means install day, 10 means app is launched 10 or more days later than installation
                .setLaunchTimes(10.toByte()) // default is 10, 3 means app is launched 3 or more times
                .setRemindInterval(1.toByte()) // default is 1, 1 means app is launched 1 or more days after neutral button clicked
                .setRemindLaunchesNumber(1.toByte()) // default is 0, 1 means app is launched 1 or more times after neutral button clicked
                .setStoreType(if (hasGooglePlay) StoreType.GOOGLEPLAY else StoreType.AMAZON)

            rater.monitor() // Monitors the app launch times

            // Only ask to rate if the user has a suitable store
            AppRate.showRateDialogIfMeetsConditions(context)     // Shows the Rate Dialog when conditions are met
        }
    }

    override fun googleSignIn(data: Intent?) {
// The Task returned from this call is always completed, no need to attach a listener.
        val task: Task<GoogleSignInAccount> =
            GoogleSignIn.getSignedInAccountFromIntent(data)
    }
}