package com.beatbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import androidx.recyclerview.widget.LinearLayoutManager
import com.beatbridge.databinding.ActivityMainBinding
import androidx.core.content.edit
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val deviceList = mutableListOf<BtDevice>()
    private lateinit var deviceAdapter: DeviceAdapter
    private var pendingCompanionAddress: String? = null

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

    private val companionAssociationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val address = pendingCompanionAddress ?: return@registerForActivityResult
        pendingCompanionAddress = null
        if (result.resultCode == RESULT_OK && CompanionDeviceSupport.finishAssociation(this, address)) {
            Toast.makeText(
                this,
                getString(R.string.background_reliability_enabled),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                getString(R.string.companion_setup_skipped),
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
        setupLanguageSwitcher()
        checkPermissionsAndLoad()
        updateStatusLabel()
        binding.tvWhatsNew.setOnClickListener {
            startActivity(Intent(this, WhatsNewActivity::class.java))
        }
        binding.tvSourceCode.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/brandonp2412/BeatBridge".toUri()))
        }
        binding.tvDonate.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/sponsors/brandonp2412".toUri()))
        }

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

    private fun setupLanguageSwitcher() {
        updateLanguageSetting()
        binding.languageSetting.setOnClickListener { showLanguagePicker() }
    }

    private fun showLanguagePicker() {
        val selectedTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selectedIndex = SUPPORTED_LANGUAGE_TAGS.indexOf(selectedTag)
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: 0
        val labels = buildList {
            add(getString(R.string.system_default))
            addAll(SUPPORTED_LANGUAGE_TAGS.map(::languageLabel))
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val locales = if (which == 0) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(SUPPORTED_LANGUAGE_TAGS[which - 1])
                }
                dialog.dismiss()
                AppCompatDelegate.setApplicationLocales(locales)
            }
            .show()
    }

    private fun updateLanguageSetting() {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        binding.tvLanguageValue.text = if (appLocales.isEmpty) {
            getString(R.string.system_default)
        } else {
            languageLabel(appLocales.get(0)?.toLanguageTag().orEmpty())
        }
    }

    private fun languageLabel(languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag)
        return locale.getDisplayName(locale).replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(locale) else character.toString()
        }
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
            CompanionDeviceSupport.removeAssociation(this, device.address)
            Toast.makeText(this, getString(R.string.removed_item, displayName), Toast.LENGTH_SHORT).show()
        } else {
            current.add(device.address)
            Toast.makeText(this, getString(R.string.added_item, displayName), Toast.LENGTH_SHORT).show()
            requestCompanionAssociation(device)
        }
        prefs.edit { putStringSet(PREF_SELECTED_DEVICES, current) }
        deviceAdapter.updateSelections(current)
        updateStatusLabel()
        syncMonitorService()
    }

    private fun requestCompanionAssociation(device: BtDevice) {
        if (!CompanionDeviceSupport.isSupported(this) || pendingCompanionAddress != null) return
        CompanionDeviceSupport.requestAssociation(
            activity = this,
            address = device.address,
            onAssociationPending = { intentSender ->
                pendingCompanionAddress = device.address
                companionAssociationLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            },
            onAssociationReady = {
                Toast.makeText(
                    this,
                    getString(R.string.background_reliability_enabled),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onFailure = { error ->
                if (!error.isNullOrBlank()) {
                    Toast.makeText(
                        this,
                        getString(R.string.companion_setup_unavailable, error),
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
        )
    }

    private fun checkPermissionsAndLoad() {
        if (hasBluetoothPermissions()) {
            loadPairedDevices()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            return
        }
        permissionLauncher.launch(buildRequiredPermissions())
    }

    private fun hasBluetoothPermissions(): Boolean =
        requiredBluetoothPermissions(Build.VERSION.SDK_INT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun buildRequiredPermissions(): Array<String> {
        val perms = requiredBluetoothPermissions(Build.VERSION.SDK_INT).toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        deviceList.clear()
        deviceList.addAll(
            (bluetoothAdapter.bondedDevices ?: emptySet())
                .map { BtDevice(address = it.address, name = it.name ?: "") }
                .sortedBy { it.name.ifEmpty { it.address } }
        )
        deviceAdapter.filter(binding.etDeviceSearch.text.toString())

        val selectedAddresses = prefs.getStringSet(PREF_SELECTED_DEVICES, emptySet()) ?: emptySet()
        selectedAddresses.forEach { address ->
            if (CompanionDeviceSupport.isAssociated(this, address)) {
                CompanionDeviceSupport.startObserving(this, address)
            }
        }

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
        const val PREFS_NAME = "beatbridge_prefs"
        const val PREF_SELECTED_DEVICES = "selected_device_addresses"
        const val PREF_ANY_DEVICE = "any_device"

        internal val SUPPORTED_LANGUAGE_TAGS = listOf(
            "en",
            "ar",
            "bn",
            "cs",
            "de",
            "es",
            "fr",
            "hi",
            "id",
            "it",
            "ja",
            "ko",
            "nl",
            "pl",
            "pt-BR",
            "ro",
            "ru",
            "th",
            "tr",
            "uk",
            "ur",
            "vi",
            "zh-CN",
            "zh-TW",
        )

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
