package com.example.localvoiceime

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/** Configurations for the supported offline models. */
fun createAsrConfig(model: AsrModel, resolve: (String) -> String): OfflineModelConfig {
    val modelConfig = OfflineModelConfig(numThreads = 2, debug = false, provider = "cpu")
    when (model) {
        AsrModel.PARAFORMER_STREAMING -> error("Use the streaming recognizer")
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

    }
    return modelConfig
}
