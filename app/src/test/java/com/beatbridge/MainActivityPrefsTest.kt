package com.beatbridge

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityPrefsTest {

    @Test
    fun prefsName_isCorrect() {
        assertEquals("beatbridge_prefs", MainActivity.PREFS_NAME)
    }

    @Test
    fun prefSelectedDevicesKey_isCorrect() {
        assertEquals("selected_device_addresses", MainActivity.PREF_SELECTED_DEVICES)
    }

    @Test
    fun prefAnyDeviceKey_isCorrect() {
        assertEquals("any_device", MainActivity.PREF_ANY_DEVICE)
    }

    @Test
    fun supportedLanguageTags_coverEveryShippedLocale() {
        assertEquals(
            listOf(
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
            ),
            MainActivity.SUPPORTED_LANGUAGE_TAGS,
        )
        assertEquals(
            MainActivity.SUPPORTED_LANGUAGE_TAGS.size,
            MainActivity.SUPPORTED_LANGUAGE_TAGS.toSet().size,
        )
    }

    @Test
    fun android13BluetoothRequirements_doNotIncludeNotificationPermission() {
        val permissions = MainActivity.requiredBluetoothPermissions(Build.VERSION_CODES.TIRAMISU)

        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), permissions)
    }

    @Test
    fun preAndroid12BluetoothRequirements_includeLegacyPermissions() {
        val permissions = MainActivity.requiredBluetoothPermissions(Build.VERSION_CODES.R)

        assertEquals(
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN),
            permissions
        )
    }

    @Test
    fun monitoringDoesNotStartWithoutBluetoothPermission() {
        assertEquals(
            false,
            MainActivity.shouldMonitor(
                hasBluetoothPermissions = false,
                anyDevice = true,
                selectedAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            )
        )
    }

    @Test
    fun monitoringStartsForConfiguredDeviceWithPermission() {
        assertEquals(
            true,
            MainActivity.shouldMonitor(
                hasBluetoothPermissions = true,
                anyDevice = false,
                selectedAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            )
        )
    }

    @Test
    fun monitoringStaysStoppedWhenNothingIsConfigured() {
        assertEquals(
            false,
            MainActivity.shouldMonitor(
                hasBluetoothPermissions = true,
                anyDevice = false,
                selectedAddresses = emptySet(),
            )
        )
    }

    @Test
    fun prefKeys_areDistinct() {
        val keys = setOf(
            MainActivity.PREF_SELECTED_DEVICES,
            MainActivity.PREF_ANY_DEVICE,
        )
        assertEquals(
            "Preference keys must be unique to avoid collisions in SharedPreferences",
            2, keys.size
        )
    }
}
