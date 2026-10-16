package com.example.localvoiceime

/** Only sherpa-onnx exports listed here are supported; arbitrary ONNX files are not interchangeable. */
enum class AsrModel(val id: String, val label: String, val directory: String, val files: List<String>) {
    SENSE_VOICE("sense_voice", "SenseVoice · 标准版（推荐）", "sense-voice",
        listOf("model.int8.onnx", "tokens.txt")),
    PARAFORMER("paraformer", "Paraformer · 中英版", "paraformer-zh",
        listOf("model.int8.onnx", "tokens.txt")),
    PARAFORMER_STREAMING("paraformer_streaming", "Paraformer · 实时中英版（含标点）", "paraformer-streaming",
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "punctuation.int8.onnx")),
    WHISPER_TINY("whisper_tiny", "Whisper · 轻量版", "whisper-tiny",
        listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt")),
    WHISPER_BASE("whisper_base", "Whisper · 基础版", "whisper-base",
        listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"));

    val recordingLimitSeconds: Int
        get() = if (this == WHISPER_TINY || this == WHISPER_BASE) 25 else 60

    fun path(file: String) = "$directory/$file"

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SENSE_VOICE
    }
}
