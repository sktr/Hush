package com.beatbridge

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat

class BluetoothMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val lastHandledConnections = mutableMapOf<String, Long>()
    private var suppressSavedVolume: Int? = null
    private var suppressAttempts = 0
    private var suppressPoll: Runnable? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> device?.let {
                    Log.i(TAG, "ACL connected: ${it.address}")
                    maybeHandleDeviceConnected(it)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    device?.let {
                        Log.i(TAG, "ACL disconnected: ${it.address}")
                        lastHandledConnections.remove(it.address)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(bluetoothReceiver, filter)
        Log.i(TAG, "Monitor service created")
    }

    private fun isAudioDevice(device: BluetoothDevice): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val major = device.bluetoothClass?.majorDeviceClass ?: return false
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    private fun maybeHandleDeviceConnected(device: BluetoothDevice) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastHandledConnections[device.address]
        if (isDuplicateConnection(previous, now)) {
            Log.i(TAG, "Ignoring duplicate connection callback for ${device.address}")
            return
        }
        lastHandledConnections[device.address] = now
        handleDeviceConnected(device)
    }

    private fun handleDeviceConnected(device: BluetoothDevice) {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        val anyDevice = prefs.getBoolean(MainActivity.PREF_ANY_DEVICE, false)
        if (anyDevice && !isAudioDevice(device)) return
        if (!anyDevice) {
            val selectedAddresses = prefs.getStringSet(MainActivity.PREF_SELECTED_DEVICES, emptySet()) ?: emptySet()
            if (selectedAddresses.isEmpty() || device.address !in selectedAddresses) return
        }

        Log.i(TAG, "Handling configured device connection: ${device.address}")
        suppressAutoplay()
    }

    private fun suppressAutoplay() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        // ponytail: re-entry keeps first saved volume, mute-stuck guard + onDestroy restore
        if (suppressSavedVolume == null) {
            suppressSavedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, PlaybackBlocker.MUTE_VOLUME, 0)
        suppressPoll?.let { handler.removeCallbacks(it) }
        suppressAttempts = 0

        fun dispatchPause() {
            for ((action, keyCode) in PlaybackBlocker.pauseKeyEvents()) {
                audioManager.dispatchMediaKeyEvent(KeyEvent(action, keyCode))
            }
            Log.i(TAG, "MEDIA_PAUSE dispatched")
        }

        dispatchPause()
        val poll = object : Runnable {
            override fun run() {
                if (PlaybackBlocker.shouldContinuePolling(audioManager.isMusicActive, suppressAttempts)) {
                    dispatchPause()
                    suppressAttempts++
                    handler.postDelayed(this, PlaybackBlocker.POLL_INTERVAL_MS)
                } else {
                    restoreSuppressedVolume()
                }
            }
        }
        suppressPoll = poll
        handler.postDelayed(poll, PlaybackBlocker.POLL_INTERVAL_MS)
    }

    private fun restoreSuppressedVolume() {
        suppressPoll?.let { handler.removeCallbacks(it) }
        suppressPoll = null
        val saved = suppressSavedVolume
        suppressSavedVolume = null
        suppressAttempts = 0
        if (saved == null) return
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        Log.i(TAG, "Volume restored to $saved")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.monitor_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.persistent_notification))
            .setSmallIcon(R.drawable.ic_music_note)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_COMPANION_CONNECTED -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (!address.isNullOrBlank()) {
                    Log.i(TAG, "Companion service reported connected: $address")
                    handleCompanionConnection(address)
                }
            }
            ACTION_COMPANION_DISCONNECTED -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (!address.isNullOrBlank()) {
                    Log.i(TAG, "Companion service reported disconnected: $address")
                    lastHandledConnections.remove(address)
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun handleCompanionConnection(address: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Ignoring companion callback without BLUETOOTH_CONNECT")
            return
        }
        try {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            val device = bluetoothManager.adapter?.getRemoteDevice(address) ?: return
            maybeHandleDeviceConnected(device)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to resolve companion device $address", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        restoreSuppressedVolume()
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(bluetoothReceiver)
        Log.i(TAG, "Monitor service destroyed")
    }

    companion object {
        private const val TAG = "BeatBridge"
        private const val CHANNEL_ID = "beatbridge_monitor"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_COMPANION_CONNECTED = "com.beatbridge.action.COMPANION_CONNECTED"
        private const val ACTION_COMPANION_DISCONNECTED = "com.beatbridge.action.COMPANION_DISCONNECTED"
        private const val EXTRA_DEVICE_ADDRESS = "device_address"
        internal const val CONNECTION_DEDUPE_MS = 3_000L

        fun companionConnectionIntent(context: Context, address: String, connected: Boolean): Intent =
            Intent(context, BluetoothMonitorService::class.java)
                .setAction(if (connected) ACTION_COMPANION_CONNECTED else ACTION_COMPANION_DISCONNECTED)
                .putExtra(EXTRA_DEVICE_ADDRESS, address)

        internal fun isDuplicateConnection(
            lastHandledAt: Long?,
            now: Long,
            windowMs: Long = CONNECTION_DEDUPE_MS,
        ): Boolean = lastHandledAt != null && now - lastHandledAt in 0 until windowMs
    }
}
