package com.example.localvoiceime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** One shared native engine. Loading, decoding, switching and eviction are serialized. */
object SpeechEngine {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var engine: OfflineAsr? = null

    private fun get(context: Context, model: AsrModel): OfflineAsr {
        if (engine?.model != model) {
            engine?.close()
            engine = null
            engine = OfflineAsr(context.applicationContext, model)
        }
        return checkNotNull(engine)
    }

    fun prepare(context: Context, model: AsrModel, callback: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = runCatching { get(app, model); Unit }
            main.post { callback(result) }
        }
    }

    fun recognize(context: Context, model: AsrModel, samples: FloatArray, callback: (Result<String>) -> Unit): PendingSpeech {
        val app = context.applicationContext
        val pending = PendingSpeech(samples)
        worker.execute {
            if (!pending.cancelled) {
                val result = runCatching {
                    val recognizer = get(app, model)
                    // Do not retain the audio in a runnable-local variable during model loading.
                    pending.takeSamples()?.let { recognizer.recognize(it) }
                }
                if (!pending.cancelled) main.post {
                    if (!pending.cancelled) callback(result.map { it.orEmpty() })
                }
            }
        }
        return pending
    }

    fun deleteModel(context: Context, model: AsrModel, callback: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = runCatching {
                if (engine?.model == model) { engine?.close(); engine = null }
                val repository = ModelRepository(app)
                check(repository.directory(model).deleteRecursively()) { "无法删除语音包，请重试" }
                check(repository.stagingDirectory(model).deleteRecursively()) { "无法删除下载缓存，请重试" }
            }
            main.post { callback(result) }
        }
    }

    fun release() { worker.execute { engine?.close(); engine = null } }
}
