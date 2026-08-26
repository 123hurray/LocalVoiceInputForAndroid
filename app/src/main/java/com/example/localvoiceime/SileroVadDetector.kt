package com.example.localvoiceime

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

class SileroVadDetector(assetManager: AssetManager) : AutoCloseable {

    private val vad = Vad(
        assetManager = assetManager,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = MODEL_PATH,
                threshold = 0.5f,
                minSilenceDuration = 1.0f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 60f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        ),
    )

    fun reset() {
        vad.reset()
    }

    fun accept(samples: ShortArray, count: Int): Boolean {
        if (count <= 0) return false
        vad.acceptWaveform(FloatArray(count) { index -> samples[index] / 32768f })
        val completedSpeech = !vad.empty()
        while (!vad.empty()) vad.pop()
        return completedSpeech
    }

    override fun close() {
        vad.release()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_PATH = "sense-voice/silero_vad_v5.onnx"
    }
}
