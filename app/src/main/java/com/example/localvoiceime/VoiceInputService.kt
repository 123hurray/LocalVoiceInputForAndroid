package com.example.localvoiceime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class VoiceInputService : InputMethodService() {
    private lateinit var panel: VoiceInputPanel
    private val host = object : VoiceInputPanel.Host {
        override val context get() = this@VoiceInputService
        override val destination get() = currentInputConnection
        override val editorInfo get() = currentInputEditorInfo
        override val isIme get() = true

        override fun submit(target: Any, text: String, done: (Boolean) -> Unit) {
            done(target is InputConnection && target === currentInputConnection && target.commitText(text, 1))
        }

        override fun editKey(newline: Boolean) {
            val target = currentInputConnection ?: return
            if (newline) {
                val info = currentInputEditorInfo
                val action = EditorAction.resolve(info?.imeOptions ?: EditorInfo.IME_ACTION_NONE, info?.actionLabel?.toString(), info?.actionId ?: 0)
                if (action != null) target.performEditorAction(action.id)
                else if ((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_UNSPECIFIED) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                } else target.commitText("\n", 1)
            } else {
                val selected = target.getSelectedText(0)
                if (!selected.isNullOrEmpty()) target.commitText("", 1)
                else target.deleteSurroundingTextInCodePoints(1, 0)
            }
        }

        override fun openSettings() {
            panel.deactivate()
            openModelSettings()
        }

        override fun openModelSettings() {
            startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        @Suppress("DEPRECATION")
        override fun returnToKeyboard(showPicker: Boolean) {
            val switched = if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod() else {
                val token = window.window?.attributes?.token
                token != null && getSystemService(InputMethodManager::class.java).switchToLastInputMethod(token)
            }
            if (!switched && showPicker) {
                panel.activate()
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        }
    }

    override fun onEvaluateFullscreenMode() = false
    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        win.setWindowAnimations(0)
    }

    override fun onCreateInputView(): View {
        window.window?.navigationBarColor = VoiceStyle.background
        window.window?.setWindowAnimations(0)
        if (::panel.isInitialized) panel.destroy()
        panel = VoiceInputPanel(host)
        return panel.createView()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (::panel.isInitialized) panel.deactivate()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        window.window?.setWindowAnimations(0)
        panel.deactivate()
        panel.activate()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (::panel.isInitialized) panel.deactivate()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        if (::panel.isInitialized) panel.destroy()
        super.onDestroy()
    }
}
