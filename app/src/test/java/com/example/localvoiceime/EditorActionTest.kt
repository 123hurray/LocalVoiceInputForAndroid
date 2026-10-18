package com.example.localvoiceime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class EditorActionTest {
    @Test fun honorsEditorActionsAndCustomIds() {
        for (id in 2..7) assertEquals(id, EditorAction.resolve(id, null, 0)!!.id)
        assertEquals("发送", EditorAction.resolve(EditorInfo.IME_ACTION_SEND, null, 0)!!.label)
        assertEquals(EditorAction.Action(123, "发布"), EditorAction.resolve(0, "发布", 123))
    }
    @Test fun noEnterFlagPreventsSendingIncludingCustomActions() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertNull(EditorAction.resolve(options, null, 0))
        assertNull(EditorAction.resolve(options, "发布", 123))
        assertNull(EditorAction.resolve(EditorInfo.IME_ACTION_NONE, null, 0))
        assertNull(EditorAction.resolve(EditorInfo.IME_ACTION_UNSPECIFIED, null, 0))
    }
}
