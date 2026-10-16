package com.example.localvoiceime

/** Bounded in-memory audio; cancellation drops pending buffers even during model loading. */
class StreamingAudioQueue(private val capacity: Int = 16000 * 60) {
    private val chunks = ArrayDeque<FloatArray>()
    private var accepted = 0
    @Volatile var finished = false; private set
    @Volatile var cancelled = false; private set
    @Synchronized fun append(samples: ShortArray, count: Int): Boolean {
        if (cancelled || finished) return false
        require(count in 0..samples.size && accepted + count <= capacity)
        if (count > 0) chunks.addLast(FloatArray(count) { samples[it] / 32768f })
        accepted += count
        return true
    }
    @Synchronized fun take(): FloatArray? = if (cancelled || chunks.isEmpty()) null else chunks.removeFirst()
    @Synchronized fun finish() { finished = true }
    @Synchronized fun discard() { finished = true; chunks.clear() }
    @Synchronized fun cancel() { cancelled = true; chunks.clear() }
    @Synchronized fun hasWork() = cancelled || finished || chunks.isNotEmpty()
}
