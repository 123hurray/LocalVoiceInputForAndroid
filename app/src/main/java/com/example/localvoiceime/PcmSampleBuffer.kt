package com.example.localvoiceime

class PcmSampleBuffer(private val capacity: Int) {

    private val samples = ShortArray(capacity)
    private var size = 0

    @Synchronized
    fun clear() {
        size = 0
    }

    @Synchronized
    fun append(source: ShortArray, count: Int): Int {
        val appended = minOf(count, source.size, capacity - size).coerceAtLeast(0)
        if (appended > 0) {
            source.copyInto(samples, destinationOffset = size, endIndex = appended)
            size += appended
        }
        return appended
    }

    @Synchronized
    fun size(): Int = size

    @Synchronized
    fun toNormalizedFloatArray(): FloatArray = FloatArray(size) { index ->
        samples[index] / 32768f
    }
}
