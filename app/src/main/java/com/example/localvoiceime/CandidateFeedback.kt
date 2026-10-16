package com.example.localvoiceime

object CandidateFeedback {
    fun text(selected: Boolean, running: Boolean, hasAudio: Boolean, hasModels: Boolean,
             loaded: Boolean, hasOptions: Boolean, failures: Int): String = when {
        !selected -> ""
        running -> "正在纠错…"
        hasOptions -> if (failures > 0) "部分候选已就绪" else "候选已就绪"
        !hasAudio -> "重说后暂无音频候选"
        !hasModels -> "需下载其他语音包"
        loaded && failures > 0 -> "纠错失败，点此重试"
        loaded -> "无其他候选"
        else -> "准备纠错…"
    }
}
