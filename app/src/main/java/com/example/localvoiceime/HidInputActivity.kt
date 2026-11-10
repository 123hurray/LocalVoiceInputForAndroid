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
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

@SuppressLint("MissingPermission")
class HidInputActivity : Activity() {
    private var keyboard: HidKeyboard? = null
    private var adapter: BluetoothAdapter? = null
    private var visible = false
    private var permissionRequested = false
    private var settingsOpen = false
    private var sending = false
    private var displayedConnection: Any? = null
    private lateinit var panel: VoiceInputPanel
    private lateinit var home: View
    private lateinit var settings: ScrollView
    private lateinit var settingsStatus: TextView
    private lateinit var retry: Button
    private lateinit var discover: Button
    private lateinit var setupButtons: List<Button>

    private val inputHost = object : VoiceInputPanel.Host {
        override val context get() = this@HidInputActivity
        override val destination get() = keyboard?.connectionToken?.takeIf { visible && !settingsOpen }

        override fun submit(target: Any, text: String, done: (InputDelivery) -> Unit) {
            val session = keyboard
            if (session == null || destination !== target || !session.ready) { done(InputDelivery.FAILED); return }
            if (text.toByteArray(Charsets.UTF_8).size > HidTextFrame.MAX_BYTES) {
                android.widget.Toast.makeText(context, "这段文字太长，请缩短后发送", android.widget.Toast.LENGTH_SHORT).show()
                done(InputDelivery.FAILED); return
            }
            transmit(InputDelivery.FAILED, { callback -> session.send(text, panel::showDeliveryProgress, callback) }, done)
        }

        override fun editKey(newline: Boolean) {
            val session = keyboard ?: return
            if (destination != null && session.ready) transmit(false, { callback -> session.sendEditKey(newline, callback) }) { ok ->
                if (!ok) android.widget.Toast.makeText(context, "按键未发送，请检查连接", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        override fun openSettings() { showSettings(true) }
        override fun openModelSettings() {
            startActivity(Intent(context, SettingsActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (settingsOpen) showSettings(false) else finish()
            }
        }
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        panel = VoiceInputPanel(inputHost)
        home = panel.createView()

        val settingsBody = column()
        settingsBody.addRow(action("‹ 返回语音输入") { showSettings(false) })
        settingsBody.addRow(label("蓝牙设置", 24f))
        settingsStatus = label("", 15f)
        settingsBody.addRow(settingsStatus)
        retry = action("重新连接蓝牙输入") { enableMode(true) }
        discover = action("让 Mac 发现此手机", click = ::discoverable)
        settingsBody.addRow(retry)
        settingsBody.addRow(discover)
        settingsBody.addRow(label("在 Mac 的「系统设置 → 蓝牙」连接此手机。", 14f, VoiceStyle.muted))
        settingsBody.addRow(label("Mac 键盘识别", 18f))
        settingsBody.addRow(label("按 Mac「键盘设置助理」的当前提示点选，键盘类型选 ANSI（美国）。", 14f, VoiceStyle.muted))
        setupButtons = HidKeyboard.MacSetupKey.entries.map { key ->
            val position = if (key == HidKeyboard.MacSetupKey.LEFT_SHIFT_NEIGHBOR) "左 Shift 右侧" else "右 Shift 左侧"
            action("${key.label} · $position") { sendSetupKey(key) }.also { settingsBody.addRow(it) }
        }
        settingsBody.addRow(action("语音与模型设置") { startActivity(Intent(this, SettingsActivity::class.java)) })
        settings = page(settingsBody)
        val root = FrameLayout(this).apply {
            setBackgroundColor(VoiceStyle.background)
            addView(home, home.layoutParams)
            addView(settings, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)
        updateConnection("未连接")
        showSettings(false)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        enableMode(false)
    }

    override fun onStop() {
        visible = false
        panel.deactivate()
        displayedConnection = null
        keyboard?.pause()
        sending = false
        super.onStop()
    }

    override fun onDestroy() {
        keyboard?.close()
        keyboard = null
        panel.destroy()
        super.onDestroy()
    }

    @Deprecated("Legacy Android back callback")
    override fun onBackPressed() {
        if (settingsOpen) showSettings(false) else super.onBackPressed()
    }

    private fun showSettings(open: Boolean) {
        if (sending) return
        settingsOpen = open
        panel.deactivate()
        displayedConnection = null
        home.visibility = if (open) View.GONE else View.VISIBLE
        settings.visibility = if (open) View.VISIBLE else View.GONE
        keyboard?.refresh()
        updateConnection(keyboard?.status ?: settingsStatus.text.toString())
    }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun enableMode(interactive: Boolean) {
        if (Build.VERSION.SDK_INT < 28 || adapter == null) {
            updateConnection("此手机不支持蓝牙键盘模式")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            updateConnection("需要允许访问附近的设备")
            if (!permissionRequested || interactive) {
                permissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), CONNECT_PERMISSION)
            }
            return
        }
        val radio = adapter ?: return
        if (!radio.isEnabled) {
            updateConnection("请开启蓝牙")
            if (interactive) requestBluetooth(BluetoothAdapter.ACTION_REQUEST_ENABLE, ENABLE_RADIO)
            return
        }
        val current = keyboard
        if (current != null) {
            current.resume()
            updateConnection(current.status)
            return
        }
        val session = HidKeyboard(this, radio) { message ->
            if (visible) updateConnection(message)
        }
        keyboard = session
        updateConnection(session.status)
        session.resume()
    }

    private fun discoverable() {
        if (keyboard?.registered != true) return
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), ADVERTISE_PERMISSION)
        } else requestBluetooth(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE, DISCOVERABLE)
    }

    private fun requestBluetooth(action: String, request: Int) {
        runCatching {
            startActivityForResult(Intent(action).apply {
                if (request == DISCOVERABLE) putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            }, request)
        }.onFailure { settingsStatus.text = "无法打开系统蓝牙设置" }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!visible) return
        val allowed = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            CONNECT_PERMISSION -> if (allowed) enableMode(false) else updateConnection("未授予附近设备权限")
            ADVERTISE_PERMISSION -> if (allowed) discoverable() else { settingsStatus.text = "未允许其他设备发现此手机" }
        }
    }

    @Deprecated("Bluetooth system activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ENABLE_RADIO && visible && resultCode == RESULT_OK) enableMode(false)
    }

    private fun sendSetupKey(key: HidKeyboard.MacSetupKey) {
        val session = keyboard ?: return
        if (!settingsOpen || !session.ready) return
        transmit(false, { callback -> session.sendSetupKey(key, callback) }) { ok ->
            settingsStatus.text = if (ok) "已发送 ${key.label}，请查看 Mac 上的提示" else "按键发送失败，请检查连接"
        }
    }

    private fun <T> transmit(failure: T, start: ((T) -> Unit) -> Boolean, done: (T) -> Unit) {
        val session = keyboard ?: run { done(failure); return }
        if (sending) { done(failure); return }
        sending = runCatching {
            start { ok ->
                if (visible && keyboard === session) {
                    sending = false
                    done(ok)
                    renderSettings()
                }
            }
        }.getOrDefault(false)
        if (!sending) done(failure)
        renderSettings()
    }

    private fun updateConnection(message: String) {
        val token = inputHost.destination
        settingsStatus.text = message
        panel.setConnection(if (keyboard?.connected == true) message else "未连接")
        if (visible && !settingsOpen) {
            if (displayedConnection !== token) {
                sending = false
                panel.deactivate()
                displayedConnection = token
            }
            panel.activate()
        } else if (keyboard?.connected != true) sending = false
        renderSettings()
    }

    private fun renderSettings() {
        discover.isEnabled = !sending && keyboard?.registered == true
        retry.isEnabled = !sending
        setupButtons.forEach { it.isEnabled = !sending && keyboard?.ready == true }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    private fun page(content: View) = ScrollView(this).apply { isFillViewport = true; addView(content) }
    private fun LinearLayout.addRow(view: View) = addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    private fun label(value: String, size: Float, color: Int = VoiceStyle.text) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5))
    }
    private fun action(label: String, primary: Boolean = false, click: () -> Unit) =
        VoiceStyle.button(this, label, primary).apply { setOnClickListener { click() } }
    private fun dp(value: Int) = VoiceStyle.dp(this, value)

    private companion object {
        const val CONNECT_PERMISSION = 1
        const val ADVERTISE_PERMISSION = 2
        const val ENABLE_RADIO = 3
        const val DISCOVERABLE = 4
    }
}
