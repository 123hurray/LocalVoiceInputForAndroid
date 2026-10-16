package com.example.localvoiceime

/** Holds at most one in-memory utterance while the recognizer is preparing. */
class PendingSpeech(samples: FloatArray) {
    private var samples: FloatArray? = samples
    @Volatile var cancelled = false
        private set

    @Synchronized fun takeSamples(): FloatArray? {
        if (cancelled) return null
        return samples.also { samples = null }
    }

    @Synchronized fun cancel() {
        cancelled = true
        samples = null
    }
}
