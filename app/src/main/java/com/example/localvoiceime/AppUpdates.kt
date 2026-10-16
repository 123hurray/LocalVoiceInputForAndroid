package com.example.localvoiceime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Manual only. Never uses microphone data or requires a GitHub account/token. */
object AppUpdates {
    private const val REPO = "123hurray/LocalVoiceInputForAndroid"
    private const val APK = "LocalVoiceInput-arm64.apk"
    private val worker = Executors.newSingleThreadExecutor()
    data class Release(val version: String, val url: String, val size: Long, val digest: String)
    data class State(val busy: Boolean = false, val message: String = "", val release: Release? = null,
                     val apk: File? = null)
    @Volatile var state = State(); private set

    @Synchronized fun check(context: Context) {
        if (state.busy) return
        val app = context.applicationContext
        state = State(true, "正在检查更新…")
        worker.execute {
            try {
                val connection = connection("https://api.github.com/repos/$REPO/releases/latest")
                val json = try {
                    if (connection.responseCode == 404) {
                        state = State(message = "暂无已发布的新版本")
                        return@execute
                    }
                    check(connection.responseCode == 200) { "暂时无法检查更新，请稍后重试" }
                    val bytes = connection.inputStream.use { it.readNBytesLimited(2 * 1024 * 1024) }
                    JSONObject(String(bytes, Charsets.UTF_8))
                } finally { connection.disconnect() }
                val current = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
                val version = json.getString("tag_name")
                if (json.optBoolean("draft") || json.optBoolean("prerelease") || !ReleaseVersion.newer(version, current)) {
                    state = State(message = "已是最新版本")
                    return@execute
                }
                val assets = json.getJSONArray("assets")
                val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name") == APK }
                    ?: error("新版本安装包尚未就绪，请稍后重试")
                val url = asset.getString("browser_download_url")
                check(url.startsWith("https://github.com/$REPO/releases/download/"))
                val digest = asset.optString("digest").removePrefix("sha256:")
                check(Regex("[0-9a-fA-F]{64}").matches(digest)) { "安装包校验信息尚未就绪" }
                val size = asset.getLong("size")
                check(size in 1..300_000_000L) { "安装包大小异常" }
                state = State(message = "发现新版本 $version · ${size / 1024 / 1024} MB",
                    release = Release(version, url, size, digest))
            } catch (error: Exception) {
                state = State(message = error.message?.takeIf { it.startsWith("安装包") || it.startsWith("新版本") }
                    ?: "检查失败，请检查网络后重试")
            }
        }
    }

    @Synchronized fun download(context: Context) {
        if (state.busy) return
        val release = state.release ?: return
        val app = context.applicationContext
        state = state.copy(busy = true, message = "正在下载…", apk = null)
        worker.execute {
            val directory = File(app.cacheDir, "updates").apply { mkdirs() }
            val temporary = File(directory, "update.part")
            val destination = File(directory, "update.apk")
            try {
                destination.delete()
                val connection = connection(release.url)
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var percent = -1
                try {
                    check(connection.responseCode == 200)
                    connection.inputStream.use { input -> temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= release.size) { "安装包大小不匹配" }
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                            val progress = (total * 100 / release.size).toInt()
                            if (progress != percent) {
                                percent = progress
                                state = state.copy(message = "正在下载… $progress%")
                            }
                        }
                    } }
                } finally { connection.disconnect() }
                check(total == release.size) { "安装包下载不完整，请重试" }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(release.digest, true)) { "安装包校验失败，请重试" }
                verify(app, temporary)
                check(temporary.renameTo(destination))
                state = State(message = "下载完成，点击安装", release = release, apk = destination)
            } catch (error: Exception) {
                temporary.delete(); destination.delete()
                state = State(message = error.message?.takeIf { it.startsWith("安装包") }
                    ?: "下载失败，请检查网络后重试", release = release)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("安装包无法读取")
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(archive.packageName == context.packageName) { "安装包不属于本应用" }
        val next = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val current = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(next > current) { "安装包不是更新版本" }
        val archiveSignatures = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val installedSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(!archiveSignatures.isNullOrEmpty() && !installedSignatures.isNullOrEmpty() &&
            archiveSignatures.toSet() == installedSignatures.toSet()) { "安装包签名不一致，已停止安装" }
    }

    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000; readTimeout = 30_000
        setRequestProperty("User-Agent", "LocalVoiceInputForAndroid-Updater")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun java.io.InputStream.readNBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
