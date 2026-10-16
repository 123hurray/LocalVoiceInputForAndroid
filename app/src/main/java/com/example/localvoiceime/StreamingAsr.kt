package com.example.localvoiceime

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Native state is accessed only on SpeechEngine's worker. */
class StreamingAsr(context: Context) : AutoCloseable {
    private val directory = requireNotNull(ModelRepository(context).installedDirectory(AsrModel.PARAFORMER_STREAMING))
    private fun path(name: String) = File(directory, name).absolutePath
    val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
        modelConfig = OnlineModelConfig(
            paraformer = OnlineParaformerModelConfig(path("encoder.int8.onnx"), path("decoder.int8.onnx")),
            tokens = path("tokens.txt"), modelType = "paraformer", numThreads = 2,
            provider = "cpu", debug = false,
        ),
        enableEndpoint = false,
    ))
    private val punctuation = try {
        OfflinePunctuation(config = OfflinePunctuationConfig(OfflinePunctuationModelConfig(
            ctTransformer = path("punctuation.int8.onnx"), numThreads = 1,
        )))
    } catch (error: Throwable) { recognizer.release(); throw error }

    fun decode(stream: OnlineStream): String {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        return recognizer.getResult(stream).text.trim()
    }

    fun finish(stream: OnlineStream): String {
        // Flush the final online chunk; padding is never included in the recording timer.
        stream.acceptWaveform(FloatArray(12800), 16000)
        stream.inputFinished()
        val text = decode(stream)
        return if (text.isBlank()) text else punctuation.addPunctuation(text).trim()
    }

    override fun close() { punctuation.release(); recognizer.release() }
}
