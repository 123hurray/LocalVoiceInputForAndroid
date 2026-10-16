package com.example.localvoiceime

import android.content.Context
import org.json.JSONArray
import java.io.File

data class ModelAsset(val name: String, val url: String, val size: Long, val sha256: String)
data class ModelPackage(val revision: String, val files: List<ModelAsset>) {
    val size: Long get() = files.sumOf { it.size }
}

class ModelRepository(context: Context) {
    private val context = context.applicationContext
    private val catalog = JSONArray(this.context.assets.open("model-catalog.json").bufferedReader().use { it.readText() })

    fun spec(model: AsrModel): ModelPackage {
        val entry = (0 until catalog.length()).map { catalog.getJSONObject(it) }
            .first { it.getString("id") == model.id }
        val files = entry.getJSONArray("files")
        return ModelPackage(entry.getString("revision"), (0 until files.length()).map { i ->
            val file = files.getJSONObject(i)
            ModelAsset(file.getString("name"), file.getString("url"), file.getLong("size"), file.getString("sha256"))
        })
    }

    fun directory(model: AsrModel): File = File(context.noBackupFilesDir, "speech-models/${model.id}-${spec(model).revision}")
    fun stagingDirectory(model: AsrModel) = File(directory(model).path + ".pending")
    fun installedDirectory(model: AsrModel): File? = directory(model).takeIf { dir ->
        File(dir, "verified").isFile && spec(model).files.all { File(dir, it.name).length() == it.size }
    }
    fun hasBundled(model: AsrModel) = model.files.all { name ->
        runCatching { context.assets.open(model.path(name)).use { it.read() >= 0 } }.getOrDefault(false)
    }
    fun isInstalled(model: AsrModel) = installedDirectory(model) != null || hasBundled(model)
}
