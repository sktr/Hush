package com.sktr.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogStoreTest {

    @Test
    fun joinsEntriesWithNewlines() {
        val store = LogStore()
        store.add("a")
        store.add("b")
        assertEquals("a\nb", store.text())
    }

    @Test
    fun dropsOldestWhenFull() {
        val store = LogStore(maxEntries = 2)
        store.add("a")
        store.add("b")
        store.add("c")
        assertEquals("b\nc", store.text())
    }

    @Test
    fun clearEmptiesStore() {
        val store = LogStore()
        store.add("a")
        store.clear()
        assertEquals("", store.text())
        assertEquals(0, store.size())
    }

    @Test
    fun emptyStoreReturnsEmptyText() {
        assertTrue(LogStore().text().isEmpty())
    }
}
