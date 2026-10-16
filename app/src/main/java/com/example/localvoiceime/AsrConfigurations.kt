package com.example.localvoiceime

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/** Empty Whisper language means auto detection; "auto" is invalid in this runtime. */
fun createAsrConfig(model: AsrModel, resolve: (String) -> String): OfflineModelConfig {
    val modelConfig = OfflineModelConfig(numThreads = 2, debug = false, provider = "cpu")
    when (model) {
        AsrModel.SENSE_VOICE -> {
            modelConfig.senseVoice = OfflineSenseVoiceModelConfig(
                model = resolve("model.int8.onnx"), language = "auto",
                useInverseTextNormalization = true,
            )
            modelConfig.tokens = resolve("tokens.txt")
        }
        AsrModel.PARAFORMER -> {
            modelConfig.paraformer = OfflineParaformerModelConfig(
                model = resolve("model.int8.onnx"),
            )
            modelConfig.tokens = resolve("tokens.txt")
        }
        AsrModel.WHISPER_TINY, AsrModel.WHISPER_BASE -> {
            val size = if (model == AsrModel.WHISPER_TINY) "tiny" else "base"
            modelConfig.whisper = OfflineWhisperModelConfig(
                encoder = resolve("$size-encoder.int8.onnx"),
                decoder = resolve("$size-decoder.int8.onnx"),
                language = "", task = "transcribe",
            )
            modelConfig.tokens = resolve("$size-tokens.txt")
        }
    }
    return modelConfig
}
