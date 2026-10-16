package com.example.localvoiceime

import android.content.Context
import java.io.File
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

class OfflineAsr(context: Context, val model: AsrModel) : AutoCloseable {
    private val recognizer: OfflineRecognizer

    init {
        val repository = ModelRepository(context)
        val directory = repository.installedDirectory(model)
        require(directory != null || repository.hasBundled(model)) { "请先在设置中下载语音包" }
        fun path(name: String) = directory?.let { File(it, name).absolutePath } ?: model.path(name)
        val modelConfig = OfflineModelConfig(numThreads = 2, debug = false, provider = "cpu")
        when (model) {
            AsrModel.SENSE_VOICE -> {
                modelConfig.senseVoice = OfflineSenseVoiceModelConfig(
                    model = path("model.int8.onnx"), language = "auto",
                    useInverseTextNormalization = true,
                )
                modelConfig.tokens = path("tokens.txt")
            }
            AsrModel.PARAFORMER -> {
                modelConfig.paraformer = OfflineParaformerModelConfig(
                    model = path("model.int8.onnx"),
                )
                modelConfig.tokens = path("tokens.txt")
            }
            AsrModel.WHISPER_TINY, AsrModel.WHISPER_BASE -> {
                val size = if (model == AsrModel.WHISPER_TINY) "tiny" else "base"
                modelConfig.whisper = OfflineWhisperModelConfig(
                    encoder = path("$size-encoder.int8.onnx"),
                    decoder = path("$size-decoder.int8.onnx"),
                    language = "auto", task = "transcribe",
                )
                modelConfig.tokens = path("$size-tokens.txt")
            }
        }
        recognizer = OfflineRecognizer(
            assetManager = if (directory == null) context.assets else null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = modelConfig,
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
