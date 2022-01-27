package com.geeksville.mesh.service

import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.provider.Settings
import androidx.core.location.LocationListenerCompat
import com.geeksville.android.Logging
import com.geeksville.mesh.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class GPSTracker(private val context: Context) : LocationListenerCompat {
    private var isGPSEnabled = false
    private var isNetworkEnabled = false
    private var canGetLocation = false
    private var location: Location? = null
    private var latitude = 0.0
    private var longitude = 0.0
    private var altitude = 0.0
    private var locationManager: LocationManager? = null
    private var minimumInterval = MIN_TIME_BW_UPDATES
    private var listener: LocationListener? = null

    interface LocationListener {
        fun onLocationChanged(location: Location)
    }

    init {
        locationManager =
            context.getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onLocationChanged(location: Location) {
        this.location = location
        this.latitude = location.latitude
        this.longitude = location.longitude
        this.altitude = location.altitude
        listener?.onLocationChanged(location)
    }


    private fun getLocation(): Location? {
        // getting GPS status
        isGPSEnabled = isHardWareGPSEnabled()

        // getting network status
        isNetworkEnabled = isNetworkLocationEnabled()

        if (!isGPSEnabled && !isNetworkEnabled) {
            // no way to get recent location
        } else {
            this.canGetLocation = true
            // update network location first
            if (isNetworkEnabled) {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_BW_UPDATES, MIN_DISTANCE_CHANGE_FOR_UPDATES, this
                )
                info("network location")
                location = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                location?.let {
                    latitude = it.latitude
                    longitude = it.longitude
                    altitude = it.altitude
                }
            }
            // now try for GPS
            if (isGPSEnabled) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_BW_UPDATES, MIN_DISTANCE_CHANGE_FOR_UPDATES, this
                )
                location = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                location?.let {

                    longitude = it.longitude
                    latitude = it.latitude
                    altitude = it.altitude
                }
            }

        }
        return location
    }

    private fun isNetworkLocationEnabled(): Boolean {
        return locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }

    private fun isHardWareGPSEnabled(): Boolean {
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }

    fun getLatitude(): Double {
        return latitude
    }

    fun getLongitude(): Double {
        return longitude
    }

    fun getAltitude(): Double {
        return altitude
    }

    fun registerLocationListener(locationListener: LocationListener) {
        listener = locationListener
        getLocation()
    }

    fun unregisterLocationListener() {
        listener = null
        locationManager?.removeUpdates(this)
    }

    fun showSettingsAlert() {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.gps_setting_title))
            .setMessage(context.getString(R.string.navigate_to_gps_settings_menu))
            .setPositiveButton(context.getString(R.string.okay)) { _, _ ->
                val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                context.startActivity(settingsIntent)
            }
            .setNeutralButton(context.getString(R.string.no)) { _, _ -> }
            .show()
    }

    fun canGetLocation(): Boolean {
        return canGetLocation
    }

    fun setLocationInterval(requestInterval: Long) {
        minimumInterval = requestInterval
    }

    companion object : Logging {
        private const val MIN_DISTANCE_CHANGE_FOR_UPDATES = 10F// 10 meters
        private const val MIN_TIME_BW_UPDATES = 1000 * 60 * 1L// 1 minute
    }
}