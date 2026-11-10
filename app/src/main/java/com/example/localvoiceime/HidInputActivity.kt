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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

@SuppressLint("MissingPermission")
class HidInputActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var keyboard: HidKeyboard? = null
    private var adapter: BluetoothAdapter? = null
    private var visible = false
    private var permissionRequested = false
    private var settingsOpen = false
    private var sending = false
    private var processing = false
    private var generation = 0
    private var capture: HidSpeechCapture? = null
    private var stream: SpeechEngine.StreamingSpeech? = null
    private var pending: PendingSpeech? = null
    private var captureStarted = 0L
    private var downY = 0f
    private var cancelling = false
    private var text = ""
    private var audio: FloatArray? = null
    private var recognizedModel: AsrModel? = null
    private var checking = false
    private var suggestion: String? = null
    private var alternate: PendingSpeech? = null
    private var candidateEpoch = 0
    private val undo = ArrayDeque<String>()
    private lateinit var home: ScrollView
    private lateinit var settings: ScrollView
    private lateinit var connection: TextView
    private lateinit var settingsStatus: TextView
    private lateinit var note: TextView
    private lateinit var gear: ImageButton
    private lateinit var liveText: TextView
    private lateinit var hold: Button
    private lateinit var review: LinearLayout
    private lateinit var words: CorrectionSelectionView
    private lateinit var send: Button
    private lateinit var retry: Button
    private lateinit var discover: Button
    private lateinit var setupButtons: List<Button>
    private lateinit var checkWords: Button
    private lateinit var apply: Button
    private lateinit var remove: Button
    private lateinit var restore: Button
    private lateinit var discard: Button

    private val ticker = object : Runnable {
        override fun run() {
            if (capture == null || captureStarted == 0L) return
            val seconds = (SystemClock.elapsedRealtime() - captureStarted) / 1000
            note.text = if (cancelling) "松手取消" else "正在听你说…  ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
            main.postDelayed(this, 100)
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
        val homeBody = column()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("蓝牙语音输入", 24f), LinearLayout.LayoutParams(0, -2, 1f))
        gear = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(VoiceStyle.muted)
            background = null
            contentDescription = "设置"
            setOnClickListener { showSettings(true) }
        }
        header.addView(gear, LinearLayout.LayoutParams(dp(48), dp(48)))
        homeBody.addRow(header)
        connection = label("未连接", 15f)
        homeBody.addRow(connection)
        note = label("连接 Mac 后即可说话", 15f, VoiceStyle.muted)
        note.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        homeBody.addRow(note)
        liveText = label("", 20f)
        liveText.maxLines = 6
        homeBody.addRow(liveText)
        hold = action("按住说话", true) { }
        hold.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    downY = event.rawY; cancelling = false
                    startVoice()
                }
                MotionEvent.ACTION_MOVE -> if (capture != null) {
                    cancelling = downY - event.rawY > dp(64)
                    hold.text = if (cancelling) "松手取消" else "松手识别"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    stopVoice(event.actionMasked == MotionEvent.ACTION_UP && !cancelling)
                }
            }
            true
        }
        // Accessibility users can click to start and click again to finish.
        hold.setOnClickListener { if (capture == null) startVoice() else stopVoice(true) }
        homeBody.addRow(hold)
        review = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words = CorrectionSelectionView(this)
        val scroll = CorrectionScrollView(this, words)
        review.addView(scroll, LinearLayout.LayoutParams(-1, dp(184)))
        words.onSelectionChanged = {
            invalidateCandidate()
            if (text.isNotEmpty() && !sending) render()
        }
        words.onSelectionFinished = { requestAlternative() }
        send = action("确认输入", true, ::sendDraft)
        review.addRow(send)
        val actions = LinearLayout(this)
        discard = action("重录") { clearDraft(); note.text = "按住说话"; render() }
        checkWords = action("查错词", click = ::checkDraft)
        remove = action("删除选中") { words.selection?.let { replaceDraft(text.removeRange(it)) } }
        restore = action("撤销") { if (undo.isNotEmpty()) showDraft(undo.removeLast()) }
        for (button in listOf(discard, checkWords, remove, restore)) {
            button.textSize = 13f; button.minWidth = 0; button.setPadding(dp(4), dp(8), dp(4), dp(8))
            actions.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        review.addRow(actions)
        apply = action("采用建议") { suggestion?.let(::replaceDraft) }
        review.addRow(apply)
        homeBody.addRow(review)
        home = page(homeBody)

        val settingsBody = column()
        settingsBody.addRow(action("‹ 返回语音输入") { showSettings(false) })
        settingsBody.addRow(label("蓝牙设置", 24f))
        settingsStatus = label("", 15f)
        settingsBody.addRow(settingsStatus)
        retry = action("重新开启蓝牙输入") { enableMode(true) }
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
            addView(home, FrameLayout.LayoutParams(-1, -1))
            addView(settings, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)
        setConnection("未连接")
        showSettings(false)
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

    @Deprecated("Legacy Android back callback")
    override fun onBackPressed() {
        if (settingsOpen) showSettings(false) else super.onBackPressed()
    }

    private fun showSettings(open: Boolean) {
        if (capture != null || processing || sending) return
        settingsOpen = open
        home.visibility = if (open) View.GONE else View.VISIBLE
        settings.visibility = if (open) View.VISIBLE else View.GONE
        render()
    }

    private fun startVoice() {
        if (!visible || settingsOpen || keyboard?.ready != true || capture != null || processing || sending || text.isNotEmpty()) return
        val model = VoiceSettingsStore(this).load().asrModel
        if (!ModelRepository(this).isInstalled(model)) {
            note.text = "请先在设置中下载并启用语音包"
            return
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            startActivity(Intent(this, MicrophonePermissionActivity::class.java))
            return
        }
        val token = ++generation
        val live = if (model == AsrModel.PARAFORMER_STREAMING) {
            SpeechEngine.beginStreaming(this, partial = { partial ->
                if (token == generation && capture != null) liveText.text = partial
            }, final = { result -> finishRecognition(token, result) })
        } else null
        stream = live
        capture = HidSpeechCapture(main, model.recordingLimitSeconds, live, { started ->
            if (token == generation && !processing) {
                captureStarted = started
                main.removeCallbacks(ticker)
                main.post(ticker)
            }
        }, { result ->
            if (token == generation) {
                capture = null
                main.removeCallbacks(ticker)
                val samples = result.getOrNull()
                if (samples == null || samples.isEmpty()) {
                    cancelVoice()
                    note.text = if (samples == null) "录音失败，请检查麦克风" else "说话时间太短，请重试"
                    render()
                } else {
                    audio = samples; recognizedModel = model; processing = true
                    note.text = "正在识别…"
                    render()
                    if (live != null) live.finish()
                    else pending = SpeechEngine.recognize(this, model, samples) { finishRecognition(token, it) }
                }
            }
        }).also { it.start() }
        note.text = "正在开启麦克风…"
        render()
    }

    private fun stopVoice(submit: Boolean) {
        val current = capture ?: return
        if (processing) return
        if (!submit) {
            cancelVoice()
            note.text = "已取消"
        } else {
            processing = true
            current.stop(true)
            main.removeCallbacks(ticker)
            note.text = "正在识别…"
        }
        render()
    }

    private fun finishRecognition(token: Int, result: Result<String>) {
        if (token != generation || !visible || keyboard?.connected != true) return
        capture?.stop(false); capture = null
        main.removeCallbacks(ticker)
        stream = null; pending = null; processing = false; liveText.text = ""
        val output = result.getOrNull().orEmpty().trim()
        if (output.isNotEmpty()) {
            showDraft(output)
            note.text = "核对文字后，点确认输入"
        } else {
            audio = null; recognizedModel = null
            note.text = if (result.isFailure) "识别失败，请重试" else "没有听清，请再说一次"
        }
        render()
    }

    private fun cancelVoice() {
        generation++
        capture?.stop(false); capture = null
        stream?.cancel(); stream = null
        pending?.cancel(); pending = null
        processing = false
        captureStarted = 0
        main.removeCallbacks(ticker)
        liveText.text = ""
        clearDraft()
    }

    private fun clearDraft() {
        audio = null; recognizedModel = null; undo.clear()
        showDraft("")
    }

    private fun showDraft(value: String) {
        text = value
        words.show(value)
        render()
    }

    private fun replaceDraft(value: String) {
        if (value == text) return
        undo.addLast(text)
        showDraft(value)
    }

    private fun invalidateCandidate() {
        candidateEpoch++
        alternate?.cancel(); alternate = null
        suggestion = null; checking = false
    }

    private fun requestAlternative() {
        val range = words.selection ?: return
        val samples = audio ?: return
        if (sending || checking || keyboard?.ready != true) return
        val repo = ModelRepository(this)
        val model = AsrModel.entries.firstOrNull { it != recognizedModel && repo.isInstalled(it) } ?: return
        val source = text
        val request = ++candidateEpoch
        checking = true
        note.text = "正在查找候选…"
        render()
        alternate = SpeechEngine.recognize(this, model, samples) { result ->
            if (request == candidateEpoch) {
                alternate = null; checking = false
                val replacement = result.getOrNull()?.let { CorrectionAlignment.alternative(source, it, range.first, range.last + 1) }
                val changed = replacement?.takeIf { it.isNotEmpty() && it != source.substring(range) }
                if (changed != null) setSuggestion(source.replaceRange(range, changed), changed)
                else { note.text = "未找到其他候选"; render() }
            }
        }
    }

    private fun checkDraft() {
        if (text.isEmpty() || sending || keyboard?.ready != true) return
        if (!MacBert.installed(this)) {
            note.text = "请先在语音与模型设置中下载错词检查包"
            return
        }
        invalidateCandidate()
        val source = text
        val range = words.selection
        val request = candidateEpoch
        checking = true; note.text = "正在查错词…"; render()
        MacBert.check(this, source) { result ->
            if (request == candidateEpoch) {
                checking = false
                val corrected = result.getOrNull()
                val scoped = if (range == null) corrected else corrected?.let { source.replaceRange(range, it.substring(range)) }
                if (scoped != null && scoped != source) {
                    val description = source.indices.filter { source[it] != scoped[it] }.joinToString("、") { "${source[it]}→${scoped[it]}" }
                    setSuggestion(scoped, description)
                } else {
                    note.text = if (corrected == null) "错词检查失败，请重试" else "未发现可修改的错词"
                    render()
                }
            }
        }
    }

    private fun setSuggestion(value: String, description: String) {
        suggestion = value
        apply.text = "采用：$description"
        note.text = "请选择是否采用建议"
        render()
    }

    private fun sendDraft() {
        val session = keyboard ?: return
        if (settingsOpen || !session.ready || text.isBlank()) return
        if (text.toByteArray(Charsets.UTF_8).size > HidTextFrame.MAX_BYTES) {
            note.text = "这段文字太长，请缩短后发送"
            return
        }
        invalidateCandidate()
        sending = runCatching {
            session.send(text) { ok ->
                if (visible && keyboard === session) {
                    sending = false
                    if (ok) clearDraft()
                    note.text = if (ok) "文字已发出" else "发送中断，草稿已保留"
                    render()
                }
            }
        }.getOrDefault(false)
        note.text = if (sending) "正在发送…" else "无法发送，请检查连接"
        render()
    }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun enableMode(interactive: Boolean) {
        closeMode()
        if (Build.VERSION.SDK_INT < 28 || adapter == null) {
            setConnection("此手机不支持蓝牙键盘模式")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            setConnection("需要允许访问附近的设备")
            if (!permissionRequested || interactive) {
                permissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), CONNECT_PERMISSION)
            }
            return
        }
        val radio = adapter ?: return
        if (!radio.isEnabled) {
            setConnection("请开启蓝牙")
            if (interactive) requestBluetooth(BluetoothAdapter.ACTION_REQUEST_ENABLE, ENABLE_RADIO)
            return
        }
        val session = HidKeyboard(this, radio) { message ->
            if (visible) {
                if (keyboard?.connected != true) {
                    sending = false
                    cancelVoice()
                }
                setConnection(message)
                if (keyboard?.connected == true) {
                    val settings = VoiceSettingsStore(this).load()
                    if (settings.keepResident && ModelRepository(this).isInstalled(settings.asrModel)) {
                        SpeechEngine.prepare(this, settings.asrModel) { }
                    }
                }
            }
        }
        keyboard = session
        setConnection("正在开启蓝牙键盘…")
        session.start()
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
            CONNECT_PERMISSION -> if (allowed) enableMode(false) else setConnection("未授予附近设备权限")
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
        sending = session.sendSetupKey(key) { ok ->
            if (visible && keyboard === session) {
                sending = false
                settingsStatus.text = if (ok) "已发送 ${key.label}，请查看 Mac 上的提示" else "按键发送失败，请检查连接"
                render()
            }
        }
        render()
    }

    private fun closeMode() {
        keyboard?.close()
        keyboard = null; sending = false
        cancelVoice()
    }

    private fun setConnection(message: String) {
        val connected = keyboard?.connected == true
        connection.text = if (connected) message else "未连接"
        settingsStatus.text = message
        if (!connected) note.text = "在右上角设置中连接 Mac 后即可说话"
        else if (text.isEmpty() && capture == null && !processing) note.text = "按住说话，松手识别"
        render()
    }

    private fun render() {
        val ready = visible && keyboard?.ready == true && !sending
        val reviewing = text.isNotEmpty() || undo.isNotEmpty()
        gear.isEnabled = capture == null && !processing && !sending
        hold.visibility = if (reviewing) View.GONE else View.VISIBLE
        hold.isEnabled = ready && !processing
        hold.text = when {
            processing -> "正在识别…"
            capture != null -> if (cancelling) "松手取消" else "松手识别"
            else -> "按住说话"
        }
        review.visibility = if (reviewing) View.VISIBLE else View.GONE
        words.isEnabled = ready
        send.isEnabled = ready && text.isNotBlank()
        discard.isEnabled = ready
        checkWords.isEnabled = ready && text.isNotEmpty() && !checking
        remove.isEnabled = ready && words.selection != null
        restore.isEnabled = ready && undo.isNotEmpty()
        apply.visibility = if (suggestion != null) View.VISIBLE else View.GONE
        apply.isEnabled = ready
        discover.isEnabled = !sending && keyboard?.registered == true
        retry.isEnabled = !sending
        setupButtons.forEach { it.isEnabled = ready }
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
