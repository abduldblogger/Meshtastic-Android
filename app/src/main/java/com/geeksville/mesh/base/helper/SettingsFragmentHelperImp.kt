package com.geeksville.mesh.base.helper

import android.view.View
import android.widget.Toast
import androidx.lifecycle.Observer
import com.geeksville.mesh.MainActivity
import com.geeksville.mesh.R
import com.geeksville.mesh.service.RadioInterfaceService
import com.geeksville.mesh.service.SerialInterface
import com.geeksville.mesh.ui.SettingsFragment

class SettingsFragmentHelperImp(
    private val settingsFragment: SettingsFragment,
    private val myActivity: MainActivity
) : SettingsFragmentHelper {
    override fun remindBLEisOff() {
        // Keep reminding user BLE is still off
        val hasUSB = SerialInterface.findDrivers(myActivity).isNotEmpty()
        if (!hasUSB) {
            // First warn about permissions, and then if needed warn about settings
            if (!myActivity.warnMissingPermissions()) {
                // Warn user if BLE is disabled
                if (settingsFragment.getBTScanModel().bluetoothAdapter?.isEnabled != true) {
                    Toast.makeText(
                        myActivity,
                        R.string.error_bluetooth,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    settingsFragment.checkLocationEnabled()
                }
            }
        }
    }

    override fun initScan(hasCompanionDeviceApi: Boolean) {
        if (hasCompanionDeviceApi)
            initModernScan()
        else
            initClassicScan()
    }

    private fun initClassicScan() {
        /// Setup the GUI to do a classic (pre SDK 26 BLE scan)
        // Turn off the widgets for the new API (we turn on/off hte classic widgets when we start scanning
        settingsFragment.changeradioButtonVisibility(View.GONE)

        settingsFragment.showClassicWidgets(View.VISIBLE)

        settingsFragment.getUIModel().bluetoothEnabled.observe(
            settingsFragment.viewLifecycleOwner,
            Observer { enabled ->
                if (enabled)
                    settingsFragment.getBTScanModel().startScan()
                else
                    settingsFragment.getBTScanModel().stopScan()
            })

        settingsFragment.getBTScanModel().errorText.observe(
            settingsFragment.viewLifecycleOwner,
            Observer { errMsg ->
                if (errMsg != null) {
                    settingsFragment.setScanStatusText(errMsg)
                }
            })

        settingsFragment.getBTScanModel().devices.observe(
            settingsFragment.viewLifecycleOwner,
            Observer { devices -> settingsFragment.updateDevicesButtons(devices) })

        settingsFragment.getUIModel().isConnected.observe(
            settingsFragment.viewLifecycleOwner,
            { settingsFragment.updateDevicesButtons(settingsFragment.getBTScanModel().devices.value) })
    }

    private fun initModernScan() {
        settingsFragment.initModernScanUI()

        val curRadio =
            RadioInterfaceService.getBondedDeviceAddress(settingsFragment.requireContext())

        if (curRadio != null) {
            settingsFragment.setScanStatusText(
                settingsFragment.getString(R.string.current_pair).format(curRadio)
            )
            settingsFragment.setRadioButtonText(settingsFragment.getString(R.string.change_radio))
        } else {
            settingsFragment.setScanStatusText(settingsFragment.getString(R.string.not_paired_yet))
            settingsFragment.setRadioButtonText(settingsFragment.getString(R.string.select_radio))
        }
        settingsFragment.startBackgroundScan()
    }
}