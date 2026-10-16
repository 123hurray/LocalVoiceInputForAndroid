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
    private var streaming: StreamingAsr? = null
    private var activeStreaming: StreamingSpeech? = null

    private fun closeStreaming() {
        activeStreaming?.cancelOnWorker()
        activeStreaming = null
        streaming?.close(); streaming = null
    }
    private fun getStreaming(context: Context): StreamingAsr {
        engine?.close(); engine = null
        return streaming ?: StreamingAsr(context).also { streaming = it }
    }

    fun beginStreaming(context: Context, partial: (String) -> Unit,
                       final: (Result<String>) -> Unit): StreamingSpeech =
        StreamingSpeech(context.applicationContext, partial, final).also { it.schedule() }

    class StreamingSpeech internal constructor(
        private val app: Context, private val partial: (String) -> Unit,
        private val final: (Result<String>) -> Unit,
    ) {
        private val audio = StreamingAudioQueue()
        private val scheduled = java.util.concurrent.atomic.AtomicBoolean(false)
        private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        private var owner: StreamingAsr? = null
        private var done = false
        private var previous = ""
        fun accept(samples: ShortArray, count: Int) { if (audio.append(samples, count)) schedule() }
        fun finish() { audio.finish(); schedule() }
        fun cancel() { audio.cancel(); schedule() }
        internal fun cancelOnWorker() { audio.cancel(); stream?.release(); stream = null; done = true }
        internal fun schedule() {
            if (scheduled.compareAndSet(false, true)) worker.execute { pump() }
        }
        private fun pump() {
            try {
                if (done) return
                if (audio.cancelled) { cancelOnWorker(); return }
                val recognizer = owner ?: getStreaming(app).also {
                    activeStreaming?.takeIf { previous -> previous !== this }?.cancelOnWorker()
                    activeStreaming = this
                    owner = it
                    stream = it.recognizer.createStream()
                }
                val current = checkNotNull(stream)
                while (!audio.cancelled) {
                    val chunk = audio.take() ?: break
                    current.acceptWaveform(chunk, 16000)
                    val text = recognizer.decode(current)
                    if (text != previous) {
                        previous = text
                        main.post { if (!audio.cancelled) partial(text) }
                    }
                }
                if (audio.cancelled) { cancelOnWorker(); return }
                if (audio.finished) {
                    val text = recognizer.finish(current)
                    current.release(); stream = null; done = true
                    main.post { if (!audio.cancelled) final(Result.success(text)) }
                }
            } catch (error: Exception) {
                stream?.release(); stream = null; done = true
                audio.discard()
                main.post { if (!audio.cancelled) final(Result.failure(error)) }
            } finally {
                if (done && activeStreaming === this) activeStreaming = null
                scheduled.set(false)
                if (!done && audio.hasWork()) schedule()
            }
        }
    }

    private fun get(context: Context, model: AsrModel): OfflineAsr {
        closeStreaming()
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
            val result = runCatching {
                if (model == AsrModel.PARAFORMER_STREAMING) getStreaming(app) else get(app, model)
                Unit
            }
            main.post { callback(result) }
        }
    }

    fun recognize(context: Context, model: AsrModel, samples: FloatArray, callback: (Result<String>) -> Unit): PendingSpeech {
        val app = context.applicationContext
        val pending = PendingSpeech(samples)
        worker.execute {
            if (!pending.cancelled) {
                val result = runCatching {
                    if (model == AsrModel.PARAFORMER_STREAMING) {
                        val recognizer = getStreaming(app)
                        pending.takeSamples()?.let { samples ->
                            val stream = recognizer.recognizer.createStream()
                            try {
                                stream.acceptWaveform(samples, 16000)
                                recognizer.decode(stream)
                                recognizer.finish(stream)
                            } finally { stream.release() }
                        }
                    } else {
                        val recognizer = get(app, model)
                        pending.takeSamples()?.let { recognizer.recognize(it) }
                    }
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
                if (model == AsrModel.PARAFORMER_STREAMING) closeStreaming()
                if (engine?.model == model) { engine?.close(); engine = null }
                val repository = ModelRepository(app)
                check(repository.directory(model).deleteRecursively()) { "无法删除语音包，请重试" }
                check(repository.stagingDirectory(model).deleteRecursively()) { "无法删除下载缓存，请重试" }
            }
            main.post { callback(result) }
        }
    }

    fun release() { worker.execute { closeStreaming(); engine?.close(); engine = null } }
}
