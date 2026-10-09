package com.sktr.hush

/** Framework-free capped log buffer. In-memory mirror of the debug file. */
class LogStore(private val maxEntries: Int = MAX_ENTRIES) {
    private val entries = ArrayDeque<String>()

    fun add(line: String) {
        if (entries.size >= maxEntries) entries.removeFirst()
        entries.addLast(line)
    }

    fun text(): String = entries.joinToString("\n")

    fun clear() = entries.clear()

    fun size(): Int = entries.size

    companion object {
        const val MAX_ENTRIES = 300
    }
}
