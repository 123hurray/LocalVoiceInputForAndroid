package com.example.localvoiceime

import android.view.inputmethod.EditorInfo

/** The editor owns Enter's action. Never infer Send from a package name. */
object EditorAction {
    data class Action(val id: Int, val label: String)
    fun resolve(options: Int, customLabel: String?, customId: Int): Action? {
        if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        if (!customLabel.isNullOrBlank()) return Action(customId, customLabel)
        val id = options and EditorInfo.IME_MASK_ACTION
        val label = when (id) {
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_NEXT -> "下一步"
            EditorInfo.IME_ACTION_PREVIOUS -> "上一步"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> return null
        }
        return Action(id, label)
    }
}
