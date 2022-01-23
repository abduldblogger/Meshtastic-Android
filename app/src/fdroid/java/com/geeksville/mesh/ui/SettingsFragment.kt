package com.geeksville.mesh.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.fragment.app.activityViewModels
import com.geeksville.android.Logging
import com.geeksville.mesh.MainActivity
import com.geeksville.mesh.R
import com.geeksville.mesh.base.helper.MeshServiceHelper
import com.geeksville.mesh.base.helper.SettingsFragmentHelper
import com.geeksville.mesh.base.helper.SettingsFragmentHelperImp
import com.geeksville.mesh.common.ui.BaseSettingsFragment
import com.geeksville.mesh.databinding.SettingsFragmentBinding
import com.geeksville.mesh.model.UIViewModel
import com.geeksville.mesh.service.MockInterface
import com.geeksville.mesh.service.RadioInterfaceService
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
        super.initUI(false, binding, model, myActivity)
    }

    private fun addDeviceButton(device: BTScanModel.DeviceListEntry, enabled: Boolean) {
        val b = RadioButton(requireActivity())
        b.text = device.name
        b.id = View.generateViewId()
        b.isEnabled = enabled
        b.isChecked =
            device.address == scanModel.selectedNotNull && device.bonded // Only show checkbox if device is still paired
        binding.deviceRadioGroup.addView(b)

        // Once we have at least one device, don't show the "looking for" animation - it makes uers think
        // something is busted
        binding.scanProgressBar.visibility = View.INVISIBLE

        b.setOnClickListener {
            if (!device.bonded) // If user just clicked on us, try to bond
                binding.scanStatusText.setText(R.string.starting_pairing)

            b.isChecked =
                scanModel.onSelected(myActivity, device)

            if (!b.isSelected)
                binding.scanStatusText.text = getString(R.string.please_pair)
        }
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
            addDeviceButton(device, true)
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
                        model.isConnected.value == MeshServiceHelper.ConnectionState.CONNECTED
                    )
                }
            } else if (scanModel.selectedUSB != null) {
                // Must be a USB device, show a placeholder disabled entry
                val curDevice = BTScanModel.DeviceListEntry(
                    scanModel.selectedUSB!!,
                    scanModel.selectedAddress!!,
                    false
                )
                addDeviceButton(curDevice, false)
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

    // If the user has not turned on location access throw up a toast warning
    fun checkLocationEnabled() {

        fun hasGps(): Boolean =
            myActivity.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

        // FIXME If they don't have google play for now we don't check for location enabled
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
        binding.provideLocationCheckbox.isChecked = false
        myActivity.registerReceiver(updateProgressReceiver, updateProgressFilter)
        settingsHelper.remindBLEisOff()
    }

    fun getBTScanModel(): BTScanModel {
        return scanModel
    }

    fun changeradioButtonVisibility(visible: Int) {
        binding.changeRadioButton.visibility = visible
    }

    fun getUIModel(): UIViewModel {
        return model
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
