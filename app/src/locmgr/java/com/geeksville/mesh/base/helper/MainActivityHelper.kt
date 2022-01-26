package com.geeksville.mesh.base.helper

import android.content.Intent
import com.geeksville.mesh.MainActivity

interface MainActivityHelper {
    fun askToRate(context: MainActivity)
    fun googleSignIn(data: Intent?)
}