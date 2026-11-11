package com.example.localvoiceime

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

@SuppressLint("MissingPermission")
class HidInputActivity : Activity() {
    private var keyboard: HidKeyboard? = null
    private var linkService: HidLinkService? = null
    private var bound = false
    private val linkChanged: (String) -> Unit = { message ->
        keyboard = linkService?.keyboard
        if (visible) updateConnection(message)
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            linkService = (binder as HidLinkService.LocalBinder).service
            linkService?.keyboard?.setInteractive(true)
            linkService?.listen(linkChanged)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            linkService?.removeListener(linkChanged)
            linkService = null; keyboard = null
            if (visible) updateConnection("蓝牙连接服务已停止，请在设置中重新开启")
        }
    }
    private var adapter: BluetoothAdapter? = null
    private var visible = false
    private var permissionRequested = false
    private var settingsOpen = false
    private var sending = false
    private var displayedConnection: Any? = null
    private lateinit var panel: VoiceInputPanel
    private lateinit var home: View
    private lateinit var pointer: HidPointerControls
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

        override val pointerControls get() = pointer
        override fun editWithKeyboard(text: String, done: (String?) -> Unit) {
            if (!visible || settingsOpen || sending) { done(null); return }
            val input = EditText(context).apply {
                setText(text)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSelection(length())
                minLines = 2; maxLines = 6
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            var result: String? = null
            val dialog = AlertDialog.Builder(context).setTitle("修改选中文字").setView(input)
                .setPositiveButton("替换") { _, _ -> result = input.text.toString() }
                .setNegativeButton("取消", null)
                .setNeutralButton("切换键盘", null)
                .create()
            dialog.setOnDismissListener { done(result) }
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                }
                input.requestFocus()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                input.post { getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
            }
            dialog.show()
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
        pointer = HidPointerControls(this,
            send = { buttons, dx, dy, wheel, click ->
                if (visible && !settingsOpen) keyboard?.sendMouse(buttons, dx, dy, wheel, click)
            },
            release = { keyboard?.resetMouse() },
        )
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
        settingsBody.addRow(action("断开并停止后台连接") {
            if (!sending && linkService != null) {
                startService(Intent(this, HidLinkService::class.java).setAction(HidLinkService.ACTION_STOP))
            }
        })
        settingsBody.addRow(action("连接已配对的 Mac", click = ::choosePairedMac))
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
        pointer.setAvailable(false)
        keyboard?.cancelInput()
        keyboard?.setInteractive(false)
        linkService?.removeListener(linkChanged)
        if (bound) { unbindService(serviceConnection); bound = false }
        linkService = null; keyboard = null
        sending = false
        super.onStop()
    }

    override fun onDestroy() {
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
        refreshPage()
    }

    private fun refreshPage() {
        panel.deactivate()
        pointer.setAvailable(false)
        displayedConnection = null
        home.visibility = if (!settingsOpen) View.VISIBLE else View.GONE
        settings.visibility = if (settingsOpen) View.VISIBLE else View.GONE
        keyboard?.refresh()
        updateConnection(keyboard?.status ?: settingsStatus.text.toString())
    }

    private fun choosePairedMac() {
        val session = keyboard ?: return
        if (sending) return
        val devices = session.pairedHosts()
        if (devices.isEmpty()) {
            settingsStatus.text = "手机还没有已配对的设备，请先让 Mac 发现此手机"
            return
        }
        AlertDialog.Builder(this).setTitle("选择已配对的 Mac")
            .setItems(devices.map(session::deviceName).toTypedArray()) { _, index ->
                if (keyboard === session && visible) session.connectTo(devices[index])
            }
            .setNegativeButton("取消", null)
            .show()
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
        val intent = Intent(this, HidLinkService::class.java)
        runCatching {
            startForegroundService(intent)
            if (!bound) bound = bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            else {
                keyboard = linkService?.keyboard
                keyboard?.resume()
                updateConnection(keyboard?.status ?: "正在开启蓝牙连接…")
            }
        }.onFailure { updateConnection("蓝牙连接服务无法启动，请重试") }
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
        pointer.setAvailable(false)
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
        panel.setConnection(message)
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
        pointer.setAvailable(visible && !settingsOpen && !sending && keyboard?.ready == true)
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
