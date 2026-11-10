package com.example.localvoiceime

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

@SuppressLint("MissingPermission") // Runtime permission checks precede Bluetooth operations.
class HidInputActivity : Activity() {
    private var keyboard: HidKeyboard? = null
    private var visible = false
    private var permissionRequested = false
    private var sending = false
    private lateinit var status: TextView
    private lateinit var draft: EditText
    private lateinit var discover: Button
    private lateinit var send: Button
    private lateinit var retry: Button
    private var adapter: BluetoothAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(VoiceStyle.background)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            isFocusableInTouchMode = true
        }
        fun label(value: String, size: Float, color: Int) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun add(view: android.view.View) {
            page.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        add(label("蓝牙语音输入", 25f, VoiceStyle.text))
        status = label("正在开启蓝牙键盘…", 15f, VoiceStyle.text).apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        add(status)
        retry = VoiceStyle.button(this, "重新开启").apply { setOnClickListener { enableMode(true) } }
        add(retry)
        discover = VoiceStyle.button(this, "让 Mac 发现此手机").apply { setOnClickListener { discoverable() } }
        add(discover)
        draft = EditText(this).apply {
            hint = "在此输入文字，或用本地语音键盘口述"
            setTextColor(VoiceStyle.text); setHintTextColor(VoiceStyle.muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3; maxLines = 6
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
        }
        add(draft)
        add(VoiceStyle.button(this, "切换输入法").apply {
            setOnClickListener {
                draft.requestFocus()
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }
        })
        send = VoiceStyle.button(this, "发送文字", primary = true).apply { setOnClickListener { sendDraft() } }
        add(send)
        add(label("在 Mac 的系统设置中配对并连接此手机。接收端需支持文本协议，离开此页会断开。", 13f, VoiceStyle.muted))
        add(VoiceStyle.button(this, "语音与模型设置").apply {
            setOnClickListener { startActivity(Intent(this@HidInputActivity, SettingsActivity::class.java)) }
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(VoiceStyle.background)
            addView(page)
        })
        page.requestFocus()
        render()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        enableMode(false)
    }

    override fun onStop() {
        visible = false
        closeMode()
        super.onStop()
    }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun enableMode(interactive: Boolean) {
        closeMode()
        if (Build.VERSION.SDK_INT < 28 || adapter == null) {
            update("此手机不支持蓝牙键盘模式（需要 Android 9 或更新版本）")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            update("需要允许访问附近的设备")
            if (!permissionRequested || interactive) {
                permissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), CONNECT_PERMISSION)
            }
            return
        }
        val radio = adapter ?: return
        if (!radio.isEnabled) {
            update("请开启蓝牙")
            if (interactive) requestBluetooth(BluetoothAdapter.ACTION_REQUEST_ENABLE, ENABLE_RADIO)
            return
        }
        val session = HidKeyboard(this, radio) { message ->
            if (visible) {
                sending = false
                update(message)
            }
        }
        keyboard = session
        update("正在开启蓝牙键盘…")
        session.start()
    }

    private fun discoverable() {
        if (keyboard?.registered != true) {
            update("请先开启蓝牙键盘")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), ADVERTISE_PERMISSION)
            return
        }
        requestBluetooth(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE, DISCOVERABLE)
    }

    private fun requestBluetooth(action: String, request: Int) {
        runCatching {
            startActivityForResult(Intent(action).apply {
                if (request == DISCOVERABLE) putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            }, request)
        }.onFailure { update("无法打开系统蓝牙设置") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!visible) return
        val allowed = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            CONNECT_PERMISSION -> if (allowed) enableMode(false) else update("未授予附近设备权限")
            ADVERTISE_PERMISSION -> if (allowed) discoverable() else update("未允许其他设备发现此手机")
        }
    }

    @Deprecated("Bluetooth system activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ENABLE_RADIO && visible && resultCode == RESULT_OK) enableMode(false)
    }

    private fun sendDraft() {
        val session = keyboard ?: return
        val source = draft.text.toString()
        if (source.isBlank()) { update("请输入要发送的文字"); return }
        if (source.toByteArray(Charsets.UTF_8).size > HidTextFrame.MAX_BYTES) {
            update("这段文字太长，请分段发送")
            return
        }
        val accepted = runCatching {
            session.send(source) { ok ->
                if (visible && keyboard === session) {
                    sending = false
                    if (ok && draft.text.toString() == source) draft.text.clear()
                    update(if (ok) "文字已发出" else "发送中断，草稿已保留")
                }
            }
        }.getOrDefault(false)
        sending = accepted
        update(if (accepted) "正在发送…" else "无法发送，请检查连接或文字")
    }

    private fun closeMode() {
        keyboard?.close()
        keyboard = null
        sending = false
    }

    private fun update(message: String) {
        status.text = message
        render()
    }

    private fun render() {
        discover.isEnabled = !sending && keyboard?.registered == true
        send.isEnabled = !sending && keyboard?.ready == true
        retry.isEnabled = !sending
    }

    private fun dp(value: Int) = VoiceStyle.dp(this, value)

    private companion object {
        const val CONNECT_PERMISSION = 1
        const val ADVERTISE_PERMISSION = 2
        const val ENABLE_RADIO = 3
        const val DISCOVERABLE = 4
    }
}
