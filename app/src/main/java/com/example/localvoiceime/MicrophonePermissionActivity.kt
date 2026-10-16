package com.example.localvoiceime

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MicrophonePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }

        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(
            LinearLayout(this).apply {
                setBackgroundColor(VoiceStyle.background)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(padding, padding, padding, padding)
                addView(
                    TextView(context).apply {
                        setTextColor(VoiceStyle.text)
                        text = getString(R.string.microphone_permission_title)
                        textSize = 20f
                        gravity = Gravity.CENTER
                    },
                    matchWidthWrapHeight(),
                )
                addView(
                    TextView(context).apply {
                        setTextColor(VoiceStyle.muted)
                        text = getString(R.string.microphone_permission_description)
                        gravity = Gravity.CENTER
                    },
                    matchWidthWrapHeight(),
                )
                addView(
                    Button(context).apply {
                        text = getString(R.string.grant_permission)
                        setOnClickListener {
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                        }
                    },
                    matchWidthWrapHeight(),
                )
            },
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) finish()
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
    }
}
