package com.example.localvoiceime

import android.content.Context
import java.io.File
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

class OfflineAsr(context: Context, val model: AsrModel) : AutoCloseable {
    private val recognizer: OfflineRecognizer

    init {
        val repository = ModelRepository(context)
        val directory = repository.installedDirectory(model)
        require(directory != null || repository.hasBundled(model)) { "请先在设置中下载语音包" }
        fun path(name: String) = directory?.let { File(it, name).absolutePath } ?: model.path(name)
        recognizer = OfflineRecognizer(
            assetManager = if (directory == null) context.assets else null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = createAsrConfig(model, ::path),
            ),
        )
    }

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
    }
}
