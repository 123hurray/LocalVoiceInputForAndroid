package com.example.localvoiceime

import android.content.Context
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.Executors

object PolishModel {
    private const val REVISION = "50968a4468ef4233ed78cd7c3de230dd1d61a56b"
    val asset = ModelAsset("Qwen3-0.6B-Q4_K_M.gguf",
        "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/$REVISION/Qwen3-0.6B-Q4_K_M.gguf",
        396705472L, "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a")
    data class State(val running: Boolean = false, val message: String = "", val bytes: Long = 0)
    @Volatile var state = State(); private set
    @Volatile private var cancelled = false
    private val worker = Executors.newSingleThreadExecutor()
    fun directory(context: Context) = File(context.noBackupFilesDir, "text-models/qwen3-$REVISION")
    fun file(context: Context) = File(directory(context), asset.name)
    fun installed(context: Context) = file(context).length() == asset.size && File(directory(context), "verified").isFile
    fun pause() { cancelled = true }
    @Synchronized fun download(context: Context) {
        if (state.running) return
        val app = context.applicationContext
        cancelled = false; state = State(true, "准备下载…")
        worker.execute {
            val dir = directory(app).apply { mkdirs() }
            val stage = File(dir, "download.part")
            try {
                check(dir.usableSpace > asset.size - stage.length() + 32 * 1024 * 1024)
                if (stage.length() >= asset.size && !DownloadIntegrity.verify(stage, asset.size, asset.sha256)) stage.delete()
                if (!DownloadIntegrity.verify(stage, asset.size, asset.sha256)) {
                    ModelDownloads.download(asset, stage, cancelled = { if (cancelled) throw InterruptedIOException() }) {
                        state = State(true, "下载中 · ${it * 100 / asset.size}%", it)
                    }
                }
                state = state.copy(message = "正在检查文字整理包…")
                check(DownloadIntegrity.verify(stage, asset.size, asset.sha256))
                if (cancelled) throw InterruptedIOException()
                check(stage.renameTo(file(app)))
                File(dir, "verified").writeText(asset.sha256)
                state = State(message = "已下载")
            } catch (_: InterruptedIOException) { state = State(message = "已暂停，下次可继续") }
            catch (_: Exception) { state = State(message = "下载未完成，请检查网络和可用空间后重试") }
        }
    }
    @Synchronized fun delete(context: Context, callback: (Boolean) -> Unit) {
        if (state.running) return
        val app = context.applicationContext
        state = State(true, "正在删除…")
        SpeechEngine.textTask {
            val success = runCatching { TextPolisher.releaseNow(); directory(app).deleteRecursively() }.getOrDefault(false)
            state = State(message = if (success) "已删除" else "删除失败，请重试")
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(success) }
        }
    }
}
