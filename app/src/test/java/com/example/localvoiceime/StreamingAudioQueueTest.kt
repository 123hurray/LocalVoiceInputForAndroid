package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class StreamingAudioQueueTest {
    @Test fun cancellationDuringLoadingDropsAudioAndRejectsNewChunks() {
        val queue = StreamingAudioQueue()
        queue.append(shortArrayOf(100, 200), 2)
        queue.cancel()
        assertNull(queue.take())
        assertFalse(queue.append(shortArrayOf(300), 1))
    }

    @Test fun finishingPreservesOrderedAudioAndRejectsLateChunks() {
        val queue = StreamingAudioQueue()
        val source = shortArrayOf(16384, -16384)
        queue.append(source, 2)
        source.fill(0) // Recorder's reused read buffer cannot change queued audio.
        queue.append(shortArrayOf(8192), 1)
        queue.finish()
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), queue.take(), 0f)
        assertArrayEquals(floatArrayOf(0.25f), queue.take(), 0f)
        assertNull(queue.take())
        assertFalse(queue.append(shortArrayOf(1), 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun totalAudioIsBoundedEvenAfterDraining() {
        val queue = StreamingAudioQueue(2)
        queue.append(shortArrayOf(1, 2), 2)
        queue.take()
        queue.append(shortArrayOf(3), 1)
    }
}
