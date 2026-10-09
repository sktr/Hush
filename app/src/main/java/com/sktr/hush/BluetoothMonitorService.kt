package com.sktr.hush

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
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
    private var audioFocusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

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
                    DebugLog.i("ACL connected: ${it.address}")
                    maybeHandleDeviceConnected(it)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    device?.let {
                        DebugLog.i("ACL disconnected: ${it.address}")
                        lastHandledConnections.remove(it.address)
                    }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTED
                    )
                    if (state == BluetoothA2dp.STATE_CONNECTED) device?.let {
                        DebugLog.i("A2DP connected: ${it.address}")
                        // ponytail: A2DP bypasses dedupe so late system volume restore is adopted
                        lastHandledConnections[it.address] = SystemClock.elapsedRealtime()
                        handleDeviceConnected(it)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        registerReceiver(bluetoothReceiver, filter)
        DebugLog.i("Monitor service created")
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
            DebugLog.i("Ignoring duplicate connection callback for ${device.address}")
            return
        }
        lastHandledConnections[device.address] = now
        handleDeviceConnected(device)
    }

    private fun handleDeviceConnected(device: BluetoothDevice) {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        val anyDevice = prefs.getBoolean(MainActivity.PREF_ANY_DEVICE, false)
        if (anyDevice && !isAudioDevice(device)) {
            DebugLog.i("Skipped non-audio device ${device.address} (any-device mode)")
            return
        }
        if (!anyDevice) {
            val selectedAddresses = prefs.getStringSet(MainActivity.PREF_SELECTED_DEVICES, emptySet()) ?: emptySet()
            if (selectedAddresses.isEmpty() || device.address !in selectedAddresses) {
                DebugLog.i("Skipped unselected device ${device.address}")
                return
            }
        }

        DebugLog.i("Handling configured device connection: ${device.address}")
        suppressAutoplay()
    }

    private fun suppressAutoplay() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        // ponytail: adopt upward only; early trigger reads pre-A2DP 0, system restores real vol later
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (PlaybackBlocker.shouldAdoptVolume(suppressSavedVolume, current)) {
            suppressSavedVolume = current
            DebugLog.i("Adopted volume $current")
        }
        DebugLog.i("Suppress start (savedVol=$suppressSavedVolume)")
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, PlaybackBlocker.MUTE_VOLUME, 0)
        stealAudioFocus(audioManager)
        suppressPoll?.let { handler.removeCallbacks(it) }
        suppressAttempts = 0

        fun dispatchStopPause() {
            for ((action, keyCode) in PlaybackBlocker.stopKeyEvents() + PlaybackBlocker.pauseKeyEvents()) {
                audioManager.dispatchMediaKeyEvent(KeyEvent(action, keyCode))
            }
            DebugLog.i("Poll #$suppressAttempts playing=${audioManager.isMusicActive} STOP+PAUSE sent")
        }

        fun broadcastMediaStop() {
            for ((action, keyCode) in PlaybackBlocker.stopKeyEvents()) {
                sendOrderedBroadcast(
                    Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
                        Intent.EXTRA_KEY_EVENT, KeyEvent(action, keyCode)
                    ),
                    null,
                )
            }
        }

        broadcastMediaStop()
        dispatchStopPause()
        val poll = object : Runnable {
            override fun run() {
                // ponytail: full-window suppression, no early restore (late autoplay slips through)
                val playing = audioManager.isMusicActive
                val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (PlaybackBlocker.shouldAdoptVolume(suppressSavedVolume, vol)) {
                    suppressSavedVolume = vol
                    DebugLog.i("Adopted volume $vol")
                }
                if (playing) {
                    if (vol != PlaybackBlocker.MUTE_VOLUME) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, PlaybackBlocker.MUTE_VOLUME, 0)
                    }
                    broadcastMediaStop()
                    dispatchStopPause()
                } else {
                    DebugLog.i("Poll #$suppressAttempts idle")
                }
                suppressAttempts++
                if (PlaybackBlocker.shouldContinuePolling(suppressAttempts)) {
                    handler.postDelayed(this, PlaybackBlocker.POLL_INTERVAL_MS)
                } else {
                    restoreSuppressedVolume()
                }
            }
        }
        suppressPoll = poll
        handler.postDelayed(poll, PlaybackBlocker.POLL_INTERVAL_MS)
    }

    private fun stealAudioFocus(audioManager: AudioManager) {
        if (audioFocusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = request
            DebugLog.i("Transient audio focus acquired")
        } else {
            DebugLog.i("Audio focus request denied")
        }
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        audioManager.abandonAudioFocusRequest(request)
        DebugLog.i("Audio focus abandoned")
    }

    private fun restoreSuppressedVolume() {
        suppressPoll?.let { handler.removeCallbacks(it) }
        suppressPoll = null
        abandonAudioFocus()
        val saved = suppressSavedVolume
        val attempts = suppressAttempts
        suppressSavedVolume = null
        suppressAttempts = 0
        if (saved == null) return
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        DebugLog.i("Volume restored to $saved (polls=$attempts)")
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
        DebugLog.i("Monitor service destroyed")
    }

    companion object {
        private const val TAG = "BeatBridge"
        private const val CHANNEL_ID = "hush_monitor"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_COMPANION_CONNECTED = "com.sktr.hush.action.COMPANION_CONNECTED"
        private const val ACTION_COMPANION_DISCONNECTED = "com.sktr.hush.action.COMPANION_DISCONNECTED"
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
