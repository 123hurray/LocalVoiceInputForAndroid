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

    fun recognize(context: Context, model: AsrModel, samples: FloatArray, callback: (Result<String>) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = runCatching { get(app, model).recognize(samples) }
            main.post { callback(result) }
        }
    }

    fun release() { worker.execute { engine?.close(); engine = null } }
}
