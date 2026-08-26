package com.example.localvoiceime

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

class SenseVoiceAsr(assetManager: AssetManager) : AutoCloseable {

    private val recognizer = OfflineRecognizer(
        assetManager = assetManager,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80,
                dither = 0f,
            ),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = MODEL_PATH,
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = TOKENS_PATH,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        ),
    )

    fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MODEL_PATH = "sense-voice/model.int8.onnx"
        private const val TOKENS_PATH = "sense-voice/tokens.txt"
    }
}
