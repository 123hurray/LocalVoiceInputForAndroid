package com.example.localvoiceime

/** Only sherpa-onnx exports listed here are supported; arbitrary ONNX files are not interchangeable. */
enum class AsrModel(val id: String, val label: String, val directory: String, val files: List<String>) {
    SENSE_VOICE("sense_voice", "SenseVoice · 标准版（推荐）", "sense-voice",
        listOf("model.int8.onnx", "tokens.txt")),
    PARAFORMER("paraformer", "Paraformer · 中英版", "paraformer-zh",
        listOf("model.int8.onnx", "tokens.txt")),
    PARAFORMER_STREAMING("paraformer_streaming", "Paraformer · 实时中英版（含标点）", "paraformer-streaming",
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "punctuation.int8.onnx"));

    val recordingLimitSeconds: Int get() = 60

    fun path(file: String) = "$directory/$file"

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SENSE_VOICE
    }
}
