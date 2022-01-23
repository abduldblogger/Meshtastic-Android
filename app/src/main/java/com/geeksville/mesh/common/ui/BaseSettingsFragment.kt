package com.geeksville.mesh.common.ui

import android.R
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.RemoteException
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioButton
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.geeksville.android.GeeksvilleApplication
import com.geeksville.android.Logging
import com.geeksville.android.hideKeyboard
import com.geeksville.mesh.MainActivity
import com.geeksville.mesh.RadioConfigProtos
import com.geeksville.mesh.android.bluetoothManager
import com.geeksville.mesh.android.hasBackgroundPermission
import com.geeksville.mesh.android.hasLocationPermission
import com.geeksville.mesh.android.usbManager
import com.geeksville.mesh.base.helper.MeshServiceHelper
import com.geeksville.mesh.common.MeshServiceCompanion
import com.geeksville.mesh.databinding.SettingsFragmentBinding
import com.geeksville.mesh.model.UIViewModel
import com.geeksville.mesh.service.*
import com.geeksville.mesh.ui.*
import com.geeksville.util.anonymize
import com.geeksville.util.exceptionReporter
import com.geeksville.util.exceptionToSnackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hoho.android.usbserial.driver.UsbSerialDriver
import java.util.regex.Pattern

object SLogging : Logging

/// Change to a new macaddr selection, updating GUI and radio
fun changeDeviceSelection(context: MainActivity, newAddr: String?) {
    // FIXME, this is a kinda yucky way to find the service
    context.model.meshService?.let { service ->
        MeshServiceCompanion.changeDeviceAddress(context, service, newAddr)
    }
}

/// Show the UI asking the user to bond with a device, call changeSelection() if/when bonding completes
private fun requestBonding(
    activity: MainActivity,
    device: BluetoothDevice,
    onComplete: (Int) -> Unit
) {
    SLogging.info("Starting bonding for ${device.anonymize}")

    // We need this receiver to get informed when the bond attempt finished
    val bondChangedReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) = exceptionReporter {
            val state =
                intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    -1
                )
            SLogging.debug("Received bond state changed $state")

            if (state != BluetoothDevice.BOND_BONDING) {
                context.unregisterReceiver(this) // we stay registered until bonding completes (either with BONDED or NONE)
                SLogging.debug("Bonding completed, state=$state")
                onComplete(state)
            }
        }
    }

    val filter = IntentFilter()
    filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    activity.registerReceiver(bondChangedReceiver, filter)

    // We ignore missing BT adapters, because it lets us run on the emulator
    device.createBond()
}

open class BaseSettingsFragment(screen: String) : ScreenFragment(screen) {

    private lateinit var myActivity: MainActivity
    private lateinit var model: UIViewModel
    private lateinit var binding: SettingsFragmentBinding

    protected val updateProgressFilter = IntentFilter(SoftwareUpdateService.ACTION_UPDATE_PROGRESS)

    protected val updateProgressReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUpdateButton(true)
        }
    }

    protected val hasCompanionDeviceApi: Boolean by lazy {
        BluetoothInterface.hasCompanionDeviceApi(requireContext())
    }

    private val deviceManager: CompanionDeviceManager by lazy {
        requireContext().getSystemService(CompanionDeviceManager::class.java)
    }

    companion object : Logging

    private val regionSpinnerListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: AdapterView<*>,
            view: View,
            position: Int,
            id: Long
        ) {
            val item = parent.getItemAtPosition(position) as String?
            val asProto = item!!.let { RadioConfigProtos.RegionCode.valueOf(it) }
            exceptionToSnackbar(requireView()) {
                model.region = asProto
            }
            updateNodeInfo() // We might have just changed Unset to set
        }

        override fun onNothingSelected(parent: AdapterView<*>) {
            //TODO("Not yet implemented")
        }
    }


    fun initUI(
        checkBoxEnabled: Boolean = false,
        binding: SettingsFragmentBinding,
        model: UIViewModel,
        myActivity: MainActivity
    ) {
        this.model = model
        this.binding = binding
        this.myActivity = myActivity
        // init our region spinner
        val spinner = binding.regionSpinner
        val regionAdapter =
            ArrayAdapter(requireContext(), R.layout.simple_spinner_item, getRegions())
        regionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = regionAdapter

        model.ownerName.observe(viewLifecycleOwner, { name ->
            binding.usernameEditText.setText(name)
        })

        // Only let user edit their name or set software update while connected to a radio
        model.isConnected.observe(viewLifecycleOwner, Observer { _ ->
            updateNodeInfo()
        })

        // Also watch myNodeInfo because it might change later
        model.myNodeInfo.observe(viewLifecycleOwner, Observer {
            updateNodeInfo()
        })

        binding.updateFirmwareButton.setOnClickListener {
            doFirmwareUpdate()
        }

        binding.usernameEditText.on(EditorInfo.IME_ACTION_DONE) {
            debug("did IME action")
            val n = binding.usernameEditText.text.toString().trim()
            if (n.isNotEmpty())
                model.setOwner(n)
            requireActivity().hideKeyboard()
        }
        binding.provideLocationCheckbox.isEnabled = checkBoxEnabled
        binding.provideLocationCheckbox.setOnCheckedChangeListener { view, isChecked ->
            if (view.isChecked) {
                debug("User changed location tracking to $isChecked")
                if (view.isPressed) { // We want to ignore changes caused by code (as opposed to the user)
                    val hasLocationPermission = myActivity.hasLocationPermission()
                    val hasBackgroundPermission = myActivity.hasBackgroundPermission()

                    // Don't check the box until the system setting changes
                    view.isChecked = hasLocationPermission && hasBackgroundPermission

                    if (!hasLocationPermission) // Make sure we have location permission (prerequisite)
                        myActivity.requestLocationPermission()
                    if (hasLocationPermission && !hasBackgroundPermission)
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(com.geeksville.mesh.R.string.background_required)
                            .setMessage(com.geeksville.mesh.R.string.why_background_required)
                            .setNeutralButton(com.geeksville.mesh.R.string.cancel) { _, _ ->
                                debug("User denied background permission")
                            }
                            .setPositiveButton(getString(com.geeksville.mesh.R.string.accept)) { _, _ ->
                                myActivity.requestBackgroundPermission()
                            }
                            .show()

                    if (view.isChecked)
                        model.provideLocation.value = isChecked
                    model.meshService?.setupProvideLocation()
                }
            } else {
                model.provideLocation.value = isChecked
                model.meshService?.stopProvideLocation()
            }
        }

        val app = (requireContext().applicationContext as GeeksvilleApplication)

        // Set analytics checkbox
        binding.analyticsOkayCheckbox.isChecked = app.isAnalyticsAllowed

        binding.analyticsOkayCheckbox.setOnCheckedChangeListener { _, isChecked ->
            debug("User changed analytics to $isChecked")
            app.isAnalyticsAllowed = isChecked
            binding.reportBugButton.isEnabled = app.isAnalyticsAllowed
        }

        // report bug button only enabled if analytics is allowed
        binding.reportBugButton.isEnabled = app.isAnalyticsAllowed
        binding.reportBugButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(com.geeksville.mesh.R.string.report_a_bug)
                .setMessage(getString(com.geeksville.mesh.R.string.report_bug_text))
                .setNeutralButton(com.geeksville.mesh.R.string.cancel) { _, _ ->
                    debug("Decided not to report a bug")
                }
                .setPositiveButton(getString(com.geeksville.mesh.R.string.report)) { _, _ ->
                    reportError("Clicked Report A Bug")
                }
                .show()
        }
    }

    private fun getRegions(): List<String> {
        /// the sorted list of region names like arrayOf("US", "CN", "EU488")
        return RadioConfigProtos.RegionCode.values().filter {
            it != RadioConfigProtos.RegionCode.UNRECOGNIZED
        }.map {
            it.name
        }.sorted()
    }

    /**
     * Pull the latest device info from the model and into the GUI
     */
    private fun updateNodeInfo() {
        val connected = model.isConnected.value

        val isConnected = connected == MeshServiceHelper.ConnectionState.CONNECTED
        binding.nodeSettings.visibility = if (isConnected) View.VISIBLE else View.GONE

        if (connected == MeshServiceHelper.ConnectionState.DISCONNECTED)
            model.ownerName.value = ""

        // update the region selection from the device
        val region = model.region
        val spinner = binding.regionSpinner
        val unsetIndex = getRegions().indexOf(RadioConfigProtos.RegionCode.Unset.name)
        spinner.onItemSelectedListener = null

        debug("current region is $region")
        var regionIndex = getRegions().indexOf(region.name)
        if (regionIndex == -1) // Not found, probably because the device has a region our app doesn't yet understand.  Punt and say Unset
            regionIndex = unsetIndex

        // We don't want to be notified of our own changes, so turn off listener while making them
        spinner.setSelection(regionIndex, false)
        spinner.onItemSelectedListener = regionSpinnerListener
        spinner.isEnabled = true

        // If actively connected possibly let the user update firmware
        refreshUpdateButton(region != RadioConfigProtos.RegionCode.Unset)

        // Update the status string (highest priority messages first)
        val info = model.myNodeInfo.value
        val statusText = binding.scanStatusText
        val permissionsWarning = myActivity.getMissingMessage()
        when {
            permissionsWarning != null ->
                statusText.text = permissionsWarning

            region == RadioConfigProtos.RegionCode.Unset ->
                statusText.text = getString(com.geeksville.mesh.R.string.must_set_region)

            connected == MeshServiceHelper.ConnectionState.CONNECTED -> {
                val fwStr = info?.firmwareString ?: "unknown"
                statusText.text = getString(com.geeksville.mesh.R.string.connected_to).format(fwStr)
            }
            connected == MeshServiceHelper.ConnectionState.DISCONNECTED ->
                statusText.text = getString(com.geeksville.mesh.R.string.not_connected)
            connected == MeshServiceHelper.ConnectionState.DEVICE_SLEEP ->
                statusText.text = getString(com.geeksville.mesh.R.string.connected_sleeping)
        }
    }

    /// Set the correct update button configuration based on current progress
    protected fun refreshUpdateButton(enable: Boolean) {
        debug("Reiniting the update button")
        val info = model.myNodeInfo.value
        val service = model.meshService
        if (model.isConnected.value == MeshServiceHelper.ConnectionState.CONNECTED && info != null && info.shouldUpdate && info.couldUpdate && service != null) {
            binding.updateFirmwareButton.visibility = View.VISIBLE
            binding.updateFirmwareButton.text =
                getString(com.geeksville.mesh.R.string.update_to).format(getString(com.geeksville.mesh.R.string.short_firmware_version))

            val progress = service.updateStatus

            binding.updateFirmwareButton.isEnabled = enable &&
                    (progress < 0) // if currently doing an upgrade disable button

            if (progress >= 0) {
                binding.updateProgressBar.progress = progress // update partial progress
                binding.scanStatusText.setText(com.geeksville.mesh.R.string.updating_firmware)
                binding.updateProgressBar.visibility = View.VISIBLE
            } else
                when (progress) {
                    SoftwareUpdateService.ProgressSuccess -> {
                        binding.scanStatusText.setText(com.geeksville.mesh.R.string.update_successful)
                        binding.updateProgressBar.visibility = View.GONE
                    }
                    SoftwareUpdateService.ProgressNotStarted -> {
                        // Do nothing - because we don't want to overwrite the status text in this case
                        binding.updateProgressBar.visibility = View.GONE
                    }
                    else -> {
                        binding.scanStatusText.setText(com.geeksville.mesh.R.string.update_failed)
                        binding.updateProgressBar.visibility = View.VISIBLE
                    }
                }
            binding.updateProgressBar.isEnabled = false

        } else {
            binding.updateFirmwareButton.visibility = View.GONE
            binding.updateProgressBar.visibility = View.GONE
        }
    }

    private fun doFirmwareUpdate() {
        model.meshService?.let { service ->

            debug("User started firmware update")
            binding.updateFirmwareButton.isEnabled = false // Disable until things complete
            binding.updateProgressBar.visibility = View.VISIBLE
            binding.updateProgressBar.progress = 0 // start from scratch

            exceptionToSnackbar(requireView()) {
                // We rely on our broadcast receiver to show progress as this progresses
                service.startFirmwareUpdate()
            }
        }
    }

    /// Start running the modern scan, once it has one result we enable the
    fun startBackgroundScan() {
        // Disable the change button until our scan has some results
        binding.changeRadioButton.isEnabled = false

        // To skip filtering based on name and supported feature flags (UUIDs),
        // don't include calls to setNamePattern() and addServiceUuid(),
        // respectively. This example uses Bluetooth.
        // We only look for Mesh (rather than the full name) because NRF52 uses a very short name
        val deviceFilter: BluetoothDeviceFilter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("Mesh.*"))
            // .addServiceUuid(ParcelUuid(RadioInterfaceService.BTM_SERVICE_UUID), null)
            .build()

        // The argument provided in setSingleDevice() determines whether a single
        // device name or a list of device names is presented to the user as
        // pairing options.
        val pairingRequest: AssociationRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        // When the app tries to pair with the Bluetooth device, show the
        // appropriate pairing request dialog to the user.
        deviceManager.associate(
            pairingRequest,
            object : CompanionDeviceManager.Callback() {

                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    debug("Found one device - enabling button")
                    binding.changeRadioButton.isEnabled = true
                    binding.changeRadioButton.setOnClickListener {
                        debug("User clicked BLE change button")

                        // Request code seems to be ignored anyways
                        startIntentSenderForResult(
                            chooserLauncher,
                            MainActivity.RC_SELECT_DEVICE, null, 0, 0, 0, null
                        )
                    }
                }

                override fun onFailure(error: CharSequence?) {
                    warn("BLE selection service failed $error")
                    // changeDeviceSelection(mainActivity, null) // deselect any device
                }
            }, null
        )
    }

    protected fun addDeviceButton(
        device: BTScanModel.DeviceListEntry,
        enabled: Boolean,
        scanModel: BTScanModel
    ) {
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
                binding.scanStatusText.setText(com.geeksville.mesh.R.string.starting_pairing)

            b.isChecked =
                scanModel.onSelected(myActivity, device)

            if (!b.isSelected)
                binding.scanStatusText.text = getString(com.geeksville.mesh.R.string.please_pair)
        }
    }

    class BTScanModel(app: Application) : AndroidViewModel(app), Logging {

        private val context: Context get() = getApplication<Application>().applicationContext

        init {
            debug("BTScanModel created")
        }

        open class DeviceListEntry(val name: String, val address: String, val bonded: Boolean) {
            val bluetoothAddress
                get() =
                    if (isBluetooth)
                        address.substring(1)
                    else
                        null


            override fun toString(): String {
                return "DeviceListEntry(name=${name.anonymize}, addr=${address.anonymize})"
            }

            val isBluetooth: Boolean get() = address[0] == 'x'
            val isSerial: Boolean get() = address[0] == 's'
        }

        class USBDeviceListEntry(usbManager: UsbManager, val usb: UsbSerialDriver) :
            DeviceListEntry(
                usb.device.deviceName,
                SerialInterface.toInterfaceName(usb.device.deviceName),
                SerialInterface.assumePermission || usbManager.hasPermission(usb.device)
            )

        override fun onCleared() {
            super.onCleared()
            debug("BTScanModel cleared")
        }

        val bluetoothAdapter = context.bluetoothManager?.adapter
        private val usbManager get() = context.usbManager

        var selectedAddress: String? = null
        val errorText = object : MutableLiveData<String?>(null) {}

        private var scanner: BluetoothLeScanner? = null

        /// If this address is for a bluetooth device, return the macaddr portion, else null
        val selectedBluetooth: String?
            get() = selectedAddress?.let { a ->
                if (a[0] == 'x')
                    a.substring(1)
                else
                    null
            }

        /// If this address is for a USB device, return the macaddr portion, else null
        val selectedUSB: String?
            get() = selectedAddress?.let { a ->
                if (a[0] == 's')
                    a.substring(1)
                else
                    null
            }

        /// Use the string for the NopInterface
        val selectedNotNull: String get() = selectedAddress ?: "n"

        private val scanCallback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                val msg = "Unexpected bluetooth scan failure: $errorCode"
                errormsg(msg)
                // error code2 seeems to be indicate hung bluetooth stack
                errorText.value = msg
            }

            // For each device that appears in our scan, ask for its GATT, when the gatt arrives,
            // check if it is an eligable device and store it in our list of candidates
            // if that device later disconnects remove it as a candidate
            override fun onScanResult(callbackType: Int, result: ScanResult) {

                if ((result.device.name?.startsWith("Mesh") == true)) {
                    val addr = result.device.address
                    val fullAddr = "x$addr" // full address with the bluetooh prefix
                    // prevent logspam because weill get get lots of redundant scan results
                    val isBonded = result.device.bondState == BluetoothDevice.BOND_BONDED
                    val oldDevs = devices.value!!
                    val oldEntry = oldDevs[fullAddr]
                    if (oldEntry == null || oldEntry.bonded != isBonded) { // Don't spam the GUI with endless updates for non changing nodes
                        val entry = DeviceListEntry(
                            result.device.name
                                ?: "unnamed-$addr", // autobug: some devices might not have a name, if someone is running really old device code?
                            fullAddr,
                            isBonded
                        )
                        // If nothing was selected, by default select the first valid thing we see
                        val activity: MainActivity? = try {
                            GeeksvilleApplication.currentActivity as MainActivity? // Can be null if app is shutting down
                        } catch (_: ClassCastException) {
                            // Buggy "Z812" phones apparently have the wrong class type for this
                            errormsg("Unexpected class for main activity")
                            null
                        }

                        if (selectedAddress == null && entry.bonded && activity != null)
                            changeScanSelection(
                                activity,
                                fullAddr
                            )
                        addDevice(entry) // Add/replace entry
                    }
                }
            }
        }

        private fun addDevice(entry: DeviceListEntry) {
            val oldDevs = devices.value!!
            oldDevs[entry.address] = entry // Add/replace entry
            devices.value = oldDevs // trigger gui updates
        }

        fun stopScan() {
            if (scanner != null) {
                debug("stopping scan")
                try {
                    scanner?.stopScan(scanCallback)
                } catch (ex: Throwable) {
                    warn("Ignoring error stopping scan, probably BT adapter was disabled suddenly: ${ex.message}")
                }
                scanner = null
            }
        }

        /**
         * returns true if we could start scanning, false otherwise
         */
        fun startScan(): Boolean {
            debug("BTScan component active")
            selectedAddress = RadioInterfaceService.getDeviceAddress(context)

            return if (bluetoothAdapter == null || MockInterface.addressValid(context, "")) {
                warn("No bluetooth adapter.  Running under emulation?")

                val testnodes = listOf(
                    DeviceListEntry("Included simulator", "m", true),
                    DeviceListEntry("Complete simulator", "t10.0.2.2", true),
                    DeviceListEntry(context.getString(com.geeksville.mesh.R.string.none), "n", true)
                    /* Don't populate fake bluetooth devices, because we don't want testlab inside of google
                    to try and use them.

                    DeviceListEntry("Meshtastic_ab12", "xaa", false),
                    DeviceListEntry("Meshtastic_32ac", "xbb", true) */
                )

                devices.value = (testnodes.map { it.address to it }).toMap().toMutableMap()

                // If nothing was selected, by default select the first thing we see
                if (selectedAddress == null)
                    changeScanSelection(
                        GeeksvilleApplication.currentActivity as MainActivity,
                        testnodes.first().address
                    )

                true
            } else {
                /// The following call might return null if the user doesn't have bluetooth access permissions
                val s: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner

                val usbDrivers = SerialInterface.findDrivers(context)

                /* model.bluetoothEnabled.value */

                if (s == null && usbDrivers.isEmpty()) {
                    errorText.value =
                        context.getString(com.geeksville.mesh.R.string.requires_bluetooth)

                    false
                } else {
                    if (scanner == null) {

                        // Clear the old device list
                        devices.value?.clear()

                        // Include a placeholder for "None"
                        addDevice(
                            DeviceListEntry(
                                context.getString(com.geeksville.mesh.R.string.none),
                                "n",
                                true
                            )
                        )

                        usbDrivers.forEach { d ->
                            addDevice(
                                USBDeviceListEntry(usbManager, d)
                            )
                        }

                        if (s != null) { // could be null if bluetooth is disabled
                            debug("starting scan")

                            // filter and only accept devices that have our service
                            val filter =
                                ScanFilter.Builder()
                                    // Samsung doesn't seem to filter properly by service so this can't work
                                    // see https://stackoverflow.com/questions/57981986/altbeacon-android-beacon-library-not-working-after-device-has-screen-off-for-a-s/57995960#57995960
                                    // and https://stackoverflow.com/a/45590493
                                    // .setServiceUuid(ParcelUuid(BluetoothInterface.BTM_SERVICE_UUID))
                                    .build()

                            val settings =
                                ScanSettings.Builder()
                                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                                    .build()
                            s.startScan(listOf(filter), settings, scanCallback)
                            scanner = s
                        }
                    } else {
                        debug("scan already running")
                    }

                    true
                }
            }
        }

        val devices =
            object : MutableLiveData<MutableMap<String, DeviceListEntry>>(mutableMapOf()) {

                /**
                 * Called when the number of active observers change from 1 to 0.
                 *
                 *
                 * This does not mean that there are no observers left, there may still be observers but their
                 * lifecycle states aren't [Lifecycle.State.STARTED] or [Lifecycle.State.RESUMED]
                 * (like an Activity in the back stack).
                 *
                 *
                 * You can check if there are observers via [.hasObservers].
                 */
                override fun onInactive() {
                    super.onInactive()
                    stopScan()
                }
            }

        /// Called by the GUI when a new device has been selected by the user
        /// Returns true if we were able to change to that item
        fun onSelected(activity: MainActivity, it: DeviceListEntry): Boolean {
            // If the device is paired, let user select it, otherwise start the pairing flow
            if (it.bonded) {
                changeScanSelection(activity, it.address)
                return true
            } else {
                // Handle requestng USB or bluetooth permissions for the device
                debug("Requesting permissions for the device")

                exceptionReporter {
                    val bleAddress = it.bluetoothAddress
                    if (bleAddress != null) {
                        // Request bonding for bluetooth
                        // We ignore missing BT adapters, because it lets us run on the emulator
                        bluetoothAdapter
                            ?.getRemoteDevice(bleAddress)?.let { device ->
                                requestBonding(activity, device) { state ->
                                    if (state == BluetoothDevice.BOND_BONDED) {
                                        errorText.value =
                                            activity.getString(com.geeksville.mesh.R.string.pairing_completed)
                                        changeScanSelection(
                                            activity,
                                            it.address
                                        )
                                    } else {
                                        errorText.value =
                                            activity.getString(com.geeksville.mesh.R.string.pairing_failed_try_again)
                                    }

                                    // Force the GUI to redraw
                                    devices.value = devices.value
                                }
                            }
                    }
                }

                if (it.isSerial) {
                    it as USBDeviceListEntry

                    val ACTION_USB_PERMISSION = "com.geeksville.mesh.USB_PERMISSION"

                    val usbReceiver = object : BroadcastReceiver() {

                        override fun onReceive(context: Context, intent: Intent) {
                            if (ACTION_USB_PERMISSION == intent.action) {

                                val device: UsbDevice =
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)!!

                                if (intent.getBooleanExtra(
                                        UsbManager.EXTRA_PERMISSION_GRANTED,
                                        false
                                    )
                                ) {
                                    info("User approved USB access")
                                    changeScanSelection(activity, it.address)

                                    // Force the GUI to redraw
                                    devices.value = devices.value
                                } else {
                                    errormsg("USB permission denied for device $device")
                                }
                            }
                            // We don't need to stay registered
                            activity.unregisterReceiver(this)
                        }
                    }

                    val permissionIntent =
                        PendingIntent.getBroadcast(activity, 0, Intent(ACTION_USB_PERMISSION), 0)
                    val filter = IntentFilter(ACTION_USB_PERMISSION)
                    activity.registerReceiver(usbReceiver, filter)
                    usbManager.requestPermission(it.usb.device, permissionIntent)
                }

                return false
            }
        }

        /// Change to a new macaddr selection, updating GUI and radio
        fun changeScanSelection(context: MainActivity, newAddr: String) {
            try {
                info("Changing device to ${newAddr.anonymize}")
                changeDeviceSelection(context, newAddr)
                selectedAddress =
                    newAddr // do this after changeDeviceSelection, so if it throws the change will be discarded
                devices.value = devices.value // Force a GUI update
            } catch (ex: RemoteException) {
                errormsg("Failed talking to service, probably it is shutting down $ex.message")
                // ignore the failure and the GUI won't be updating anyways
            }
        }
    }


}