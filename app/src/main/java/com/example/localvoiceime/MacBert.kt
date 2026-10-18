package com.example.localvoiceime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.InterruptedIOException
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Explicit download/check only. Native work, deletion and expiry share one serial worker. */
object MacBert {
    private const val REVISION = "615e6e09ef9a69ec487bc7c641ec3a311e2c11b9"
    private const val BASE = "https://huggingface.co/shibing624/macbert4csc-base-chinese/resolve/$REVISION/"
    val files = listOf(
        ModelAsset("model.onnx", BASE + "onnx/model.onnx", 474336933,
            "a4d9d6807c0c8bc9d014462a728e7212315630c669a6bd670b653154391a73f3"),
        ModelAsset("vocab.txt", BASE + "vocab.txt", 109540,
            "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"))
    val size get() = files.sumOf { it.size }
    data class State(val busy: Boolean = false, val downloading: Boolean = false, val bytes: Long = 0, val message: String = "")
    @Volatile var state = State(); private set
    @Volatile private var cancelled = false
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var session: OrtSession? = null
    private var tokenizer: MacBertText? = null
    private var expiry: ScheduledFuture<*>? = null
    private fun directory(context: Context) = File(context.noBackupFilesDir, "text-models/macbert-$REVISION")
    private fun staging(context: Context) = File(directory(context).path + ".pending")
    fun installed(context: Context): Boolean = runCatching { directory(context).let { dir ->
        File(dir, "verified").let { it.isFile && it.readText() == REVISION } && files.all { File(dir, it.name).length() == it.size }
    } }.getOrDefault(false)
    fun canDelete(context: Context) = directory(context).exists() || staging(context).exists()

    @Synchronized fun download(context: Context) {
        if (state.busy || installed(context)) return
        val app = context.applicationContext
        cancelled = false; state = State(true, true, message = "准备下载…")
        worker.execute {
            try {
                val stage = staging(app)
                check(stage.mkdirs() || stage.isDirectory)
                require(stage.usableSpace > size - stage.listFiles().orEmpty().sumOf { it.length() } + 32L * 1024 * 1024) { "可用空间不足" }
                var completed = 0L
                fun checkCancelled() { if (cancelled) throw InterruptedIOException() }
                for (asset in files) {
                    checkCancelled()
                    val file = File(stage, asset.name)
                    if (!DownloadIntegrity.verify(file, asset.size, asset.sha256)) {
                        if (file.length() >= asset.size) check(file.delete())
                        ModelDownloads.download(asset, file, { checkCancelled() }) { received ->
                            state = State(true, true, completed + received, "下载中")
                        }
                        state = state.copy(message = "正在检查文件…")
                        checkCancelled()
                        if (!DownloadIntegrity.verify(file, asset.size, asset.sha256)) { file.delete(); error("文件检查未通过") }
                    }
                    completed += asset.size
                }
                checkCancelled()
                File(stage, "verified").writeText(REVISION)
                check(stage.renameTo(directory(app)))
                state = State(message = "下载完成")
            } catch (_: InterruptedIOException) { state = State(message = "已暂停，下次可继续") }
            catch (e: Exception) { state = State(message = if (e.message == "可用空间不足") "可用空间不足" else "下载未完成，请检查网络后重试") }
        }
    }
    fun cancelDownload() { cancelled = true }
    @Synchronized fun delete(context: Context, callback: (Boolean) -> Unit) {
        if (state.busy) return
        val app = context.applicationContext
        state = State(true, message = "正在删除…")
        worker.execute {
            close()
            val success = runCatching { directory(app).deleteRecursively() && staging(app).deleteRecursively() }.getOrDefault(false)
            state = State(message = if (success) "已删除" else "删除未完成，请重试")
            main.post { callback(success) }
        }
    }
    @Synchronized fun check(context: Context, text: String, callback: (Result<String>) -> Unit) {
        if (state.busy) { main.post { callback(Result.failure(IllegalStateException("请稍后再试"))) }; return }
        val app = context.applicationContext
        state = State(true, message = "正在检查错词…")
        worker.execute {
            val result = runCatching {
                check(installed(app)) { "请先在设置中下载错词检查包" }
                require(text.length <= 4096) { "文字过长，请缩短后再试" }
                expiry?.cancel(false)
                if (session == null) {
                    tokenizer = MacBertText(File(directory(app), "vocab.txt").readLines())
                    OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        session = OrtEnvironment.getEnvironment().createSession(File(directory(app), "model.onnx").path, options)
                    }
                }
                predict(text)
            }
            if (result.isFailure) close()
            else expiry = worker.schedule({ close() }, 60, TimeUnit.SECONDS)
            state = State()
            main.post { callback(result) }
        }
    }
    fun release() { worker.execute { close() } }
    private fun close() {
        expiry?.cancel(false); expiry = null
        runCatching { session?.close() }; session = null; tokenizer = null
    }
    private fun predict(text: String): String {
        val tokenizer = checkNotNull(tokenizer)
        val tokens = tokenizer.tokenize(text)
        val output = text.toCharArray()
        val env = OrtEnvironment.getEnvironment()
        // Every token is covered once, with context on both sides. No silent truncation of long drafts.
        for ((core, coreEnd, start, end) in MacBertText.windows(tokens.size)) {
            val ids = longArrayOf(tokenizer.cls) + tokens.subList(start, end).map { it.id }.toLongArray() + tokenizer.sep
            val shape = longArrayOf(1, ids.size.toLong())
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { input ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { mask ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape).use { types ->
                        checkNotNull(session).run(mapOf("input_ids" to input, "attention_mask" to mask, "token_type_ids" to types)).use { result ->
                            val tensor = result[0] as OnnxTensor
                            val scores = tensor.floatBuffer
                            val vocabularySize = tokenizer.vocabulary.size
                            check(tensor.info.shape.contentEquals(longArrayOf(1, ids.size.toLong(), vocabularySize.toLong())))
                            for (index in core until coreEnd) {
                                val base = (index - start + 1) * vocabularySize
                                var best = 0
                                for (id in 1 until vocabularySize) if (scores.get(base + id) > scores.get(base + best)) best = id
                                tokenizer.replacement(text, tokens[index], best)?.let { output[tokens[index].start] = it }
                            }
                        }
                    }
                }
            }
        }
        return String(output)
    }
}
