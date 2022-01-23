package com.geeksville.mesh.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import com.geeksville.android.Logging
import com.geeksville.android.isGooglePlayAvailable
import com.geeksville.mesh.MainActivity
import com.geeksville.mesh.R
import com.geeksville.mesh.android.hasBackgroundPermission
import com.geeksville.mesh.android.hasLocationPermission
import com.geeksville.mesh.base.helper.MeshServiceHelper
import com.geeksville.mesh.base.helper.SettingsFragmentHelper
import com.geeksville.mesh.base.helper.SettingsFragmentHelperImp
import com.geeksville.mesh.common.ui.BaseSettingsFragment
import com.geeksville.mesh.databinding.SettingsFragmentBinding
import com.geeksville.mesh.model.UIViewModel
import com.geeksville.mesh.service.MockInterface
import com.geeksville.mesh.service.RadioInterfaceService
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

@SuppressLint("NewApi")
class SettingsFragment : BaseSettingsFragment("Settings"), Logging {
    private var _binding: SettingsFragmentBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private val scanModel: BTScanModel by activityViewModels()
    private val model: UIViewModel by activityViewModels()

    // FIXME - move this into a standard GUI helper class
    private val guiJob = Job()
    private val mainScope = CoroutineScope(Dispatchers.Main + guiJob)

    private lateinit var myActivity: MainActivity
    private lateinit var settingsHelper: SettingsFragmentHelper

    override fun onDestroy() {
        guiJob.cancel()
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = SettingsFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }


    /// Setup the ui widgets unrelated to BLE scanning
    private fun initCommonUI() {
        super.initUI(isGooglePlayAvailable(requireContext()), binding, model, myActivity)
    }


    /// Show the GUI for classic scanning
    fun showClassicWidgets(visible: Int) {
        binding.scanProgressBar.visibility = visible
        binding.deviceRadioGroup.visibility = visible
    }

    fun updateDevicesButtons(devices: MutableMap<String, BTScanModel.DeviceListEntry>?) {
        // Remove the old radio buttons and repopulate
        binding.deviceRadioGroup.removeAllViews()

        if (devices == null) return

        val adapter = scanModel.bluetoothAdapter
        var hasShownOurDevice = false
        devices.values.forEach { device ->
            if (device.address == scanModel.selectedNotNull)
                hasShownOurDevice = true
            addDeviceButton(device, true, getBTScanModel())
        }

        // The selected device is not in the scan; it is either offline, or it doesn't advertise
        // itself (most BLE devices don't advertise when connected).
        // Show it in the list, greyed out based on connection status.
        if (!hasShownOurDevice) {
            // Note: we pull this into a tempvar, because otherwise some other thread can change selectedAddress after our null check
            // and before use
            val bleAddr = scanModel.selectedBluetooth

            if (bleAddr != null && adapter != null && adapter.isEnabled) {
                val bDevice =
                    adapter.getRemoteDevice(bleAddr)
                if (bDevice.name != null) { // ignore nodes that node have a name, that means we've lost them since they appeared
                    val curDevice = BTScanModel.DeviceListEntry(
                        bDevice.name,
                        scanModel.selectedAddress!!,
                        bDevice.bondState == BOND_BONDED
                    )
                    addDeviceButton(
                        curDevice,
                        model.isConnected.value == MeshServiceHelper.ConnectionState.CONNECTED,
                        getBTScanModel()
                    )
                }
            } else if (scanModel.selectedUSB != null) {
                // Must be a USB device, show a placeholder disabled entry
                val curDevice = BTScanModel.DeviceListEntry(
                    scanModel.selectedUSB!!,
                    scanModel.selectedAddress!!,
                    false
                )
                addDeviceButton(curDevice, false, getBTScanModel())
            }
        }

        val hasBonded =
            RadioInterfaceService.getBondedDeviceAddress(requireContext()) != null

        // get rid of the warning text once at least one device is paired.
        // If we are running on an emulator, always leave this message showing so we can test the worst case layout
        binding.warningNotPaired.visibility =
            if (hasBonded && !MockInterface.addressValid(requireContext(), ""))
                View.GONE
            else
                View.VISIBLE
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        myActivity = requireActivity() as MainActivity
        settingsHelper = SettingsFragmentHelperImp(this, myActivity)
        initCommonUI()
        settingsHelper.initScan(hasCompanionDeviceApi)
    }

    fun getUIModel(): UIViewModel {
        return model
    }

    // If the user has not turned on location access throw up a toast warning
    fun checkLocationEnabled() {

        fun hasGps(): Boolean =
            myActivity.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

        // FIXME If they don't have google play for now we don't check for location enabled
        if (hasGps() && isGooglePlayAvailable(requireContext())) {
            // We do this painful process because LocationManager.isEnabled is only SDK28 or latet
            val builder = LocationSettingsRequest.Builder()
            builder.setNeedBle(true)

            val request = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            }
            builder.addLocationRequest(request) // Make sure we are granted high accuracy permission

            val locationSettingsResponse = LocationServices.getSettingsClient(requireActivity())
                .checkLocationSettings(builder.build())

            fun weNeedAccess() {
                context?.let { c ->
                    warn("Telling user we need need location accesss")
                    Toast.makeText(
                        c,
                        getString(R.string.location_disabled_warning),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            locationSettingsResponse.addOnSuccessListener {
                if (!it.locationSettingsStates.isBleUsable || !it.locationSettingsStates.isLocationUsable)
                    weNeedAccess()
                else
                    debug("We have location access")
            }

            locationSettingsResponse.addOnFailureListener { _ ->
                errormsg("Failed to get location access")
                // We always show the toast regardless of what type of exception we receive.  Because even non
                // resolvable api exceptions mean user still needs to fix something.

                ///if (exception is ResolvableApiException) {

                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.

                // Show the dialog by calling startResolutionForResult(),
                // and check the result in onActivityResult().
                // exception.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS)

                // For now just punt and show a dialog

                // The context might be gone (if activity is going away) by the time this handler is called
                weNeedAccess()

                //} else
                //    Exceptions.report(exception)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        scanModel.stopScan()

        requireActivity().unregisterReceiver(updateProgressReceiver)
    }

    override fun onResume() {
        super.onResume()

        if (!hasCompanionDeviceApi)
            scanModel.startScan()

        // system permissions might have changed while we were away
        binding.provideLocationCheckbox.isChecked = myActivity.hasLocationPermission()
                && myActivity.hasBackgroundPermission() && (model.provideLocation.value ?: false)
                && isGooglePlayAvailable(requireContext())
        myActivity.registerReceiver(updateProgressReceiver, updateProgressFilter)
        settingsHelper.remindBLEisOff()
    }

    fun getBTScanModel(): BTScanModel {
        return scanModel
    }

    fun changeradioButtonVisibility(visible: Int) {
        binding.changeRadioButton.visibility = visible
    }

    fun setScanStatusText(errMsg: String) {
        binding.scanStatusText.text = errMsg
    }

    fun initModernScanUI() {
        // Turn off the widgets for the classic API
        binding.scanProgressBar.visibility = View.GONE
        binding.deviceRadioGroup.visibility = View.GONE
        binding.changeRadioButton.visibility = View.VISIBLE
    }

    fun setRadioButtonText(string: String) {
        binding.changeRadioButton.text = string
    }
}
