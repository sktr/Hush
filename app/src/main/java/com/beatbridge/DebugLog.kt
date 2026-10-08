package com.beatbridge

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent debug log for field troubleshooting. Every entry goes to logcat
 * ("BeatBridge" tag) and to a capped file, readable in-app without adb.
 */
object DebugLog {
    private const val TAG = "BeatBridge"
    private const val FILE_NAME = "beatbridge-debug.log"
    private const val MAX_FILE_BYTES = 64 * 1024L

    private val store = LogStore()

    @Volatile
    private var dir: File? = null

    @Synchronized
    fun init(context: Context) {
        dir = context.filesDir
        if (store.size() == 0) {
            readFileLines().takeLast(LogStore.MAX_ENTRIES).forEach(store::add)
        }
    }

    /** Timestamped entry → logcat + memory + file. Thread-safe. */
    @Synchronized
    fun i(message: String) {
        Log.i(TAG, message)
        val line = "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} $message"
        store.add(line)
        appendFile(line)
    }

    @Synchronized
    fun readText(): String {
        val memory = store.text()
        return memory.ifEmpty { readFileLines().joinToString("\n") }
    }

    @Synchronized
    fun clear() {
        store.clear()
        try {
            dir?.resolve(FILE_NAME)?.delete()
        } catch (_: Exception) {
        }
    }

    private fun appendFile(line: String) {
        val file = dir?.resolve(FILE_NAME) ?: return
        try {
            file.appendText(line + "\n")
            if (file.length() > MAX_FILE_BYTES) {
                file.writeText(file.readLines().takeLast(200).joinToString("\n", postfix = "\n"))
            }
        } catch (_: Exception) {
        }
    }

    private fun readFileLines(): List<String> {
        return try {
            dir?.resolve(FILE_NAME)?.takeIf { it.exists() }?.readLines() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
