package com.example.localvoiceime

import android.content.Context
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Application-scoped, no Activity references. Partial files survive process death for retry. */
object ModelDownloads {
    data class State(val model: AsrModel? = null, val bytes: Long = 0, val total: Long = 0,
                     val message: String = "", val running: Boolean = false)
    @Volatile var state = State(); private set
    @Volatile private var cancelled = false
    private val worker = Executors.newSingleThreadExecutor()

    @Synchronized fun start(context: Context, model: AsrModel) {
        if (state.running) return
        val app = context.applicationContext
        val repository = ModelRepository(app)
        if (repository.isInstalled(model)) return
        cancelled = false
        state = State(model, total = repository.spec(model).size, message = "准备下载…", running = true)
        worker.execute {
            val spec = repository.spec(model)
            val stage = repository.stagingDirectory(model)
            try {
                check(stage.mkdirs() || stage.isDirectory)
                require(stage.usableSpace > spec.size - stage.listFiles().orEmpty().sumOf { it.length() } + 32L * 1024 * 1024) {
                    "可用空间不足，请清理后重试"
                }
                var completed = 0L
                for (asset in spec.files) {
                    checkCancelled()
                    val file = File(stage, asset.name)
                    if (!DownloadIntegrity.verify(file, asset.size, asset.sha256)) {
                        if (file.length() >= asset.size) file.delete()
                        download(asset, file) { bytes ->
                            state = State(model, completed + bytes, spec.size, "下载中", true)
                        }
                        state = state.copy(message = "正在检查语音包…")
                        checkCancelled()
                        if (!DownloadIntegrity.verify(file, asset.size, asset.sha256)) {
                            file.delete()
                            error("文件检查未通过，请重新下载")
                        }
                    }
                    completed += asset.size
                }
                checkCancelled()
                File(stage, "verified").writeText(spec.revision)
                check(stage.renameTo(repository.directory(model))) { "无法保存语音包，请重试" }
                state = State(model, spec.size, spec.size, "下载完成")
                if (VoiceSettingsStore(app).load().let { it.keepResident && it.asrModel == model }) {
                    SpeechEngine.prepare(app, model) { /* Reuse within this process; no background service. */ }
                }
            } catch (_: InterruptedIOException) {
                state = state.copy(message = "已暂停，下次可继续", running = false)
            } catch (error: Exception) {
                state = state.copy(message = if (cancelled) "已暂停，下次可继续" else
                    (if (error.message?.startsWith("可用空间") == true) error.message!! else "下载未完成，请检查网络后重试"), running = false)
            }
        }
    }

    @Synchronized fun clearDeletedState(model: AsrModel) {
        if (!state.running && state.model == model) state = State()
    }

    fun cancel() { cancelled = true }
    private fun checkCancelled() { if (cancelled) throw InterruptedIOException() }

    private fun download(asset: ModelAsset, file: File, progress: (Long) -> Unit) {
        var offset = file.length()
        var url = URL(asset.url)
        var connection: HttpURLConnection? = null
        try {
            for (redirect in 0..8) {
                checkCancelled()
                require(url.protocol == "https")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 20_000
                    setRequestProperty("Accept-Encoding", "identity")
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
                connection = conn
                val status = conn.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location") ?: error("Missing redirect")
                    url = URL(url, location)
                    conn.disconnect()
                    if (redirect == 8) error("Too many redirects")
                    continue
                }
                require(status == 200 || status == 206) { "HTTP $status" }
                if (status == 206) {
                    require(DownloadIntegrity.validRange(conn.getHeaderField("Content-Range"), offset, asset.size))
                } else offset = 0 // A server can ignore Range; restart, never append a full response.
                var received = offset
                conn.inputStream.buffered().use { input ->
                    file.outputStreamAppend(offset > 0).buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            checkCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(received + n <= asset.size) { "Unexpected size" }
                            output.write(buffer, 0, n)
                            received += n
                            progress(received)
                        }
                    }
                }
                return
            }
            error("Download failed")
        } finally { connection?.disconnect() }
    }

    private fun File.outputStreamAppend(append: Boolean) = java.io.FileOutputStream(this, append)
}
