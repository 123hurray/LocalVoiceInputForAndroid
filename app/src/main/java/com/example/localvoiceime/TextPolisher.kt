package com.example.localvoiceime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

internal object TextNative {
    init { System.loadLibrary("voice_text") }
    external fun rewrite(path: String, prompt: ByteArray, cancelled: AtomicBoolean): ByteArray
    external fun release()
}

object TextPolisher {
    private val main = Handler(Looper.getMainLooper())
    private var loaded = false // SpeechEngine worker only
    private var epoch = 0
    fun rewrite(context: Context, text: String, callback: (Result<String>) -> Unit): AtomicBoolean {
        val cancelled = AtomicBoolean(false)
        val app = context.applicationContext
        SpeechEngine.textTask {
            val request = ++epoch
            val result = runCatching {
                check(!cancelled.get() && PolishModel.installed(app))
                check(text.length <= 1000)
                loaded = true
                val bytes = TextNative.rewrite(PolishModel.file(app).absolutePath,
                    PolishPolicy.prompt(text).toByteArray(Charsets.UTF_8), cancelled)
                checkNotNull(PolishPolicy.accept(text, bytes.toString(Charsets.UTF_8)))
            }
            main.post { if (!cancelled.get()) callback(result) }
            // Reuse on demand for five minutes; no proactive loading or inference.
            main.postDelayed({ SpeechEngine.textTask { if (request == epoch) releaseNow() } }, 300_000)
        }
        return cancelled
    }
    internal fun releaseNow() {
        if (loaded) { runCatching { TextNative.release() }; loaded = false }
        epoch++
    }
}
