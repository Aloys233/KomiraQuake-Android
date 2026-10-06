package com.aloys23.komiraquake.core

import org.junit.Assert.*
import org.junit.Test

class SoundQueueTest {
    @Test fun clipsPlayInArrivalOrderAndNoneIsLost() {
        val queue = SoundQueue()
        assertTrue(queue.isEmpty())
        queue.enqueue("issue")
        queue.enqueue("warn")
        assertEquals(2, queue.size())
        assertEquals("issue", queue.take())
        assertEquals("warn", queue.take())
        assertTrue(queue.isEmpty())
        assertNull(queue.take())
    }

    @Test fun duplicateClipIsNotQueuedTwice() {
        val queue = SoundQueue()
        assertTrue(queue.enqueue("hypocenter"))
        assertFalse(queue.enqueue("hypocenter"))
        assertEquals("hypocenter", queue.take())
        assertTrue(queue.isEmpty())
    }

    @Test fun clearDiscardsPendingClips() {
        val queue = SoundQueue()
        queue.enqueue("issue")
        queue.clear()
        assertTrue(queue.isEmpty())
    }

    @Test fun overflowDropsOldestClips() {
        val queue = SoundQueue()
        repeat(20) { queue.enqueue("clip$it") }
        assertEquals(16, queue.size())
        assertEquals("clip4", queue.take())
    }

    @Test fun statementsQueueInsteadOfInterrupting() {
        val queue = SoundQueue()
        queue.enqueue("issue")
        queue.enqueue("warn")
        queue.enqueue("hypocenter")
        assertEquals(3, queue.size())
        assertEquals("issue", queue.take())
        assertEquals("warn", queue.take())
        assertEquals("hypocenter", queue.take())
    }
}