package com.sktr.hush

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import com.sktr.hush.databinding.ActivityMainBinding
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val deviceList = mutableListOf<BtDevice>()
    private lateinit var deviceAdapter: DeviceAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasBluetoothPermissions()) {
            loadPairedDevices()
            syncMonitorService()
        } else {
            syncMonitorService()
            Toast.makeText(
                this,
                getString(R.string.bluetooth_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter ?: run {
            Toast.makeText(this, getString(R.string.bluetooth_not_supported), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupDeviceRecyclerView()
        setupSearch()
        setupAnyDeviceToggle()
        setupDebugLog()
        setupBatteryRow()
        checkPermissionsAndLoad()
        updateStatusLabel()

        syncMonitorService()
    }

    private fun setupDeviceRecyclerView() {
        deviceAdapter = DeviceAdapter(
            devices = deviceList,
            selectedAddresses = prefs.getStringSet(PREF_SELECTED_DEVICES, emptySet()) ?: emptySet(),
            onSelect = { device -> onDeviceSelected(device) },
        )
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
            addItemDecoration(createListDivider())
        }
    }

    private fun setupSearch() {
        binding.etDeviceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                deviceAdapter.filter(s?.toString() ?: "")
            }
        })
    }

    private fun createListDivider() = SelectionAwareDividerDecoration(
        color = ContextCompat.getColor(this, R.color.bb_divider),
        density = resources.displayMetrics.density,
    )

    private fun setupAnyDeviceToggle() {
        val anyDevice = prefs.getBoolean(PREF_ANY_DEVICE, false)
        binding.switchAnyDevice.isChecked = anyDevice
        updateDeviceSectionEnabled(!anyDevice)
        binding.switchAnyDevice.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(PREF_ANY_DEVICE, isChecked) }
            updateDeviceSectionEnabled(!isChecked)
            updateStatusLabel()
            syncMonitorService()
        }
    }

    private fun setupBatteryRow() {
        binding.batterySetting.setOnClickListener { requestBatteryExemption() }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryLabel()
    }

    private fun isBatteryExempt(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryLabel() {
        binding.tvBatteryValue.setText(
            if (isBatteryExempt()) R.string.battery_exempt else R.string.battery_action_needed
        )
    }

    private fun requestBatteryExemption() {
        if (isBatteryExempt()) {
            updateBatteryLabel()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun setupDebugLog() {
        DebugLog.init(this)
        binding.debugLogSetting.setOnClickListener { showDebugLog() }
    }

    private fun showDebugLog() {
        val logText = DebugLog.readText().ifEmpty { getString(R.string.debug_log_empty) }
        val textView = TextView(this).apply {
            text = logText
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply {
            addView(textView)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (400 * resources.displayMetrics.density).toInt(),
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.debug_log)
            .setView(scroll)
            .setPositiveButton(R.string.action_close, null)
            .setNeutralButton(R.string.action_share) { _, _ -> shareDebugLog(logText) }
            .setNegativeButton(R.string.action_clear) { _, _ ->
                DebugLog.clear()
                Toast.makeText(this, getString(R.string.debug_log_cleared), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun shareDebugLog(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                getString(R.string.debug_log),
            )
        )
    }

    private fun updateDeviceSectionEnabled(enabled: Boolean) {
        binding.layoutDeviceSection.alpha = if (enabled) 1f else 0.38f
        binding.tilDeviceSearch.isEnabled = enabled
        binding.etDeviceSearch.isEnabled = enabled
    }

    private fun onDeviceSelected(device: BtDevice) {
        if (prefs.getBoolean(PREF_ANY_DEVICE, false)) return
        val current = LinkedHashSet(prefs.getStringSet(PREF_SELECTED_DEVICES, emptySet()) ?: emptySet())
        val displayName = device.name.ifEmpty { device.address }
        if (device.address in current) {
            current.remove(device.address)
            Toast.makeText(this, getString(R.string.removed_item, displayName), Toast.LENGTH_SHORT).show()
        } else {
            current.add(device.address)
            Toast.makeText(this, getString(R.string.added_item, displayName), Toast.LENGTH_SHORT).show()
        }
        prefs.edit { putStringSet(PREF_SELECTED_DEVICES, current) }
        deviceAdapter.updateSelections(current)
        updateStatusLabel()
        syncMonitorService()
    }

    private fun checkPermissionsAndLoad() {
        // ponytail: never request POST_NOTIFICATIONS; denied permission hides the FGS notice
        if (hasBluetoothPermissions()) {
            loadPairedDevices()
            return
        }
        permissionLauncher.launch(buildRequiredPermissions())
    }

    private fun hasBluetoothPermissions(): Boolean =
        requiredBluetoothPermissions(Build.VERSION.SDK_INT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun buildRequiredPermissions(): Array<String> =
        requiredBluetoothPermissions(Build.VERSION.SDK_INT).toTypedArray()

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        deviceList.clear()
        deviceList.addAll(
            (bluetoothAdapter.bondedDevices ?: emptySet())
                .map { BtDevice(address = it.address, name = it.name ?: "") }
                .sortedBy { it.name.ifEmpty { it.address } }
        )
        deviceAdapter.filter(binding.etDeviceSearch.text.toString())

        val isEmpty = deviceList.isEmpty()
        binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvDevices.visibility = if (isEmpty) View.GONE else View.VISIBLE
        updateStatusLabel()
    }

    private fun updateStatusLabel() {
        val selectedAddresses = prefs.getStringSet(PREF_SELECTED_DEVICES, emptySet()) ?: emptySet()
        val selectedNames = selectedAddresses.mapNotNull { address ->
            deviceList
                .find { it.address == address }
                ?.name
                ?.takeIf { it.isNotBlank() }
        }

        binding.tvStatus.text = when {
            prefs.getBoolean(PREF_ANY_DEVICE, false) -> getString(R.string.ready_any_bluetooth)
            selectedAddresses.size == 1 && selectedNames.size == 1 ->
                getString(R.string.watching_device, selectedNames.first())
            selectedAddresses.size == 1 -> getString(R.string.watching_one_device)
            selectedAddresses.size > 1 ->
                getString(R.string.watching_devices, selectedAddresses.size)
            else -> getString(R.string.choose_device)
        }
    }

    private fun syncMonitorService() {
        val selectedAddresses = prefs.getStringSet(PREF_SELECTED_DEVICES, emptySet()) ?: emptySet()
        val shouldMonitor = shouldMonitor(
            hasBluetoothPermissions = hasBluetoothPermissions(),
            anyDevice = prefs.getBoolean(PREF_ANY_DEVICE, false),
            selectedAddresses = selectedAddresses,
        )
        val serviceIntent = Intent(this, BluetoothMonitorService::class.java)
        if (shouldMonitor) {
            startForegroundService(serviceIntent)
        } else {
            stopService(serviceIntent)
        }
    }

    companion object {
        const val PREFS_NAME = "hush_prefs"
        const val PREF_SELECTED_DEVICES = "selected_device_addresses"
        const val PREF_ANY_DEVICE = "any_device"
        const val PREF_LAST_KNOWN_VOLUME = "last_known_music_volume"

        internal fun requiredBluetoothPermissions(sdkInt: Int): List<String> =
            if (sdkInt >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
            }

        internal fun shouldMonitor(
            hasBluetoothPermissions: Boolean,
            anyDevice: Boolean,
            selectedAddresses: Set<String>,
        ): Boolean = hasBluetoothPermissions && (anyDevice || selectedAddresses.isNotEmpty())
    }
}
