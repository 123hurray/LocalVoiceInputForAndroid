package com.example.localvoiceime

import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class VoiceInputService : InputMethodService() {

    private lateinit var statusView: TextView

    override fun onCreateInputView(): View {
        val padding = (16 * resources.displayMetrics.density).toInt()

        statusView = TextView(this).apply {
            text = getString(R.string.ime_name)
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setTypeface(typeface, Typeface.BOLD)
        }

        val testButton = Button(this).apply {
            text = getString(R.string.test_input)
            setOnClickListener {
                val committed = currentInputConnection?.commitText(
                    getString(R.string.test_text),
                    1,
                ) == true
                statusView.text = if (committed) {
                    getString(R.string.test_text)
                } else {
                    getString(R.string.ime_name)
                }
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
            addView(
                statusView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                testButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::statusView.isInitialized) {
            statusView.text = getString(R.string.ime_name)
        }
    }
}
