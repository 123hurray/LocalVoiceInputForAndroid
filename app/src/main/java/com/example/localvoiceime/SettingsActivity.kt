package com.example.localvoiceime

import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var store: VoiceSettingsStore
    private lateinit var repository: ModelRepository
    private lateinit var selectedLabel: TextView
    private lateinit var quickStart: Switch
    private lateinit var autoReturn: Switch
    private data class ModelRow(val progress: TextView, val download: Button, val use: Button, val delete: Button)
    private val rows = mutableMapOf<AsrModel, ModelRow>()
    private lateinit var correctionSwitch: Switch
    private lateinit var updateStatus: TextView
    private lateinit var updateAction: Button
    private lateinit var checkUpdate: Button
    private var installWhenReady = false
    private var awaitingInstallPermission = false
    private var deleting = false
    private val handler = Handler(Looper.getMainLooper())
    private var selected = AsrModel.SENSE_VOICE
    private var preparedModel: AsrModel? = null
    private var initialising = true
    private var keyboardHeightDp = 320

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = VoiceStyle.background
        window.navigationBarColor = VoiceStyle.background
        store = VoiceSettingsStore(this)
        repository = ModelRepository(this)
        val settings = store.load()
        selected = settings.asrModel
        keyboardHeightDp = settings.keyboardHeightDp
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(VoiceStyle.background)
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        page.addView(label("本地语音", 28f, VoiceStyle.text))
        page.addView(label("按住说话，松手输入", 16f, VoiceStyle.muted))
        page.addView(label("语音始终在本机处理，仅下载语音包、手动检查和下载更新时使用网络。", 14f, VoiceStyle.muted))
        page.addView(section("开始使用"))
        page.addView(action("启用本地语音键盘") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        page.addView(action("允许使用麦克风") {
            startActivity(Intent(this, MicrophonePermissionActivity::class.java))
        })
        page.addView(label("启用后，从当前键盘切换到「本地语音」。按住按钮说话，中途停顿不会结束；上滑可取消。", 14f, VoiceStyle.muted))

        page.addView(section("使用习惯"))
        quickStart = Switch(this).apply {
            text = "快速启动"; textSize = 17f; setTextColor(VoiceStyle.text)
            minHeight = dp(56); isChecked = settings.keepResident
            setOnCheckedChangeListener { _, enabled ->
                if (!initialising) {
                    saveSettings()
                    if (enabled) { prepareSelected() }
                    else { preparedModel = null; SpeechEngine.release() }
                }
            }
        }
        page.addView(quickStart)
        page.addView(label("保留已准备的语音包，下次打开更快。不显示常驻通知；系统清理后会自动重新准备。", 14f, VoiceStyle.muted))
        autoReturn = Switch(this).apply {
            text = "输入后返回原键盘"; textSize = 17f; setTextColor(VoiceStyle.text)
            minHeight = dp(56); isChecked = settings.autoReturnToPreviousIme
            setOnCheckedChangeListener { _, _ -> if (!initialising) saveSettings() }
        }
        page.addView(autoReturn)
        page.addView(section("键盘高度"))
        val heightLabel = label("面板高度：$keyboardHeightDp dp", 16f, VoiceStyle.text)
        page.addView(heightLabel)
        page.addView(SeekBar(this).apply {
            max = 300
            progress = keyboardHeightDp - 200
            contentDescription = "语音键盘高度，200 到 500"
            minHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    keyboardHeightDp = progress + 200
                    heightLabel.text = "面板高度：$keyboardHeightDp dp"
                    if (fromUser) saveSettings()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        page.addView(label("调整到与常用键盘一致，减少切换时页面上下移动。下次打开语音输入时生效。", 14f, VoiceStyle.muted))
        page.addView(section("语音包"))
        selectedLabel = label("", 15f, VoiceStyle.accent)
        page.addView(selectedLabel)
        page.addView(label("先下载，再选择使用。下载时请保持此页打开；中断后可以继续。", 14f, VoiceStyle.muted))
        for (model in AsrModel.entries) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = VoiceStyle.rounded(context, VoiceStyle.surface)
            }
            card.addView(label(model.label, 18f, VoiceStyle.text))
            card.addView(label(description(model), 13f, VoiceStyle.muted))
            val progress = label("", 14f, VoiceStyle.muted)
            card.addView(progress)
            val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val download = VoiceStyle.button(this, "下载").apply {
                setOnClickListener {
                    if (ModelDownloads.state.let { it.running && it.model == model }) ModelDownloads.cancel()
                    else ModelDownloads.start(this@SettingsActivity, model)
                    renderModels()
                }
            }
            val use = VoiceStyle.button(this, "使用", primary = true).apply {
                setOnClickListener {
                    if (repository.isInstalled(model)) {
                        selected = model; saveSettings(); preparedModel = null
                        prepareSelected(); renderModels()
                    }
                }
            }
            val delete = VoiceStyle.button(this, "删除").apply {
                setTextColor(VoiceStyle.muted)
                setOnClickListener { confirmDelete(model) }
            }
            buttons.addView(download, LinearLayout.LayoutParams(0, dp(50), 1f))
            buttons.addView(use, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(10) })
            buttons.addView(delete, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(10) })
            card.addView(buttons)
            rows[model] = ModelRow(progress, download, use, delete)
            page.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        page.addView(label("首次准备需要一点时间。快速启动可减少之后的等待；系统清理后台后需要重新准备。", 13f, VoiceStyle.muted))
        page.addView(section("纠错"))
        correctionSwitch = Switch(this).apply {
            text = "纠错"; textSize = 17f; setTextColor(VoiceStyle.text)
            minHeight = dp(56); isChecked = settings.correctionEnabled
            setOnCheckedChangeListener { _, enabled ->
                if (!initialising) {
                    store.save(store.load().copy(correctionEnabled = enabled))
                }
            }
        }
        page.addView(correctionSwitch)
        page.addView(label("开启后先检查文字，再确认输入。也可在语音面板随时开关。", 14f, VoiceStyle.muted))
        page.addView(section("应用更新"))
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        page.addView(label("当前版本 $currentVersion", 15f, VoiceStyle.muted))
        updateStatus = label("点击检查 GitHub 上的最新正式版本。", 14f, VoiceStyle.muted)
        page.addView(updateStatus)
        checkUpdate = action("检查更新") { AppUpdates.check(this); renderUpdates() }
        page.addView(checkUpdate)
        updateAction = action("下载并安装") {
            if (AppUpdates.state.apk?.exists() == true) installUpdate()
            else { installWhenReady = true; AppUpdates.download(this); renderUpdates() }
        }
        page.addView(updateAction)
        awaitingInstallPermission = savedInstanceState?.getBoolean("awaitingInstallPermission") ?: false
        renderUpdates()
        setContentView(ScrollView(this).apply { setBackgroundColor(VoiceStyle.background); addView(page) })
        initialising = false
        renderModels()
    }

    override fun onResume() {
        super.onResume()
        val settings = store.load()
        selected = settings.asrModel
        initialising = true
        quickStart.isChecked = settings.keepResident
        correctionSwitch.isChecked = settings.correctionEnabled
        initialising = false
        preparedModel = null
        handler.post(refresh)
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            if (packageManager.canRequestPackageInstalls()) installUpdate()
            else android.widget.Toast.makeText(this, "未允许安装，可稍后点击安装重试", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    private val refresh = object : Runnable {
        override fun run() { renderModels(); renderUpdates(); prepareSelected(); handler.postDelayed(this, 600) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaitingInstallPermission", awaitingInstallPermission)
        super.onSaveInstanceState(outState)
    }
    private fun renderUpdates() {
        val state = AppUpdates.state
        if (state.message.isNotEmpty()) updateStatus.text = state.message
        checkUpdate.isEnabled = !state.busy
        updateAction.visibility = if (state.release != null) android.view.View.VISIBLE else android.view.View.GONE
        updateAction.isEnabled = !state.busy
        updateAction.text = if (state.apk?.exists() == true) "安装更新" else "下载并安装"
        if (installWhenReady && !state.busy) {
            installWhenReady = false
            if (state.apk?.exists() == true) installUpdate()
        }
    }
    private fun installUpdate() {
        val apk = AppUpdates.state.apk?.takeIf { it.exists() } ?: return
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this).setTitle("允许安装更新")
                    .setMessage("请在接下来的系统页面允许本地语音安装应用，返回后继续安装。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("去设置") { _, _ ->
                        try {
                            awaitingInstallPermission = true
                            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")))
                        } catch (_: Exception) {
                            awaitingInstallPermission = false
                            updateStatus.text = "无法打开安装权限设置，请在系统设置中允许安装应用"
                        }
                    }.show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.updates", apk)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            updateStatus.text = "无法打开安装程序，请稍后重试"
        }
    }
    private fun prepareSelected() {
        if (deleting || !quickStart.isChecked || !repository.isInstalled(selected) || preparedModel == selected) return
        preparedModel = selected
        SpeechEngine.prepare(this, selected) { /* Shared engine remains ready for the IME. */ }
    }
    private fun saveSettings() = store.save(store.load().copy(asrModel = selected, keepResident = quickStart.isChecked,
        autoReturnToPreviousIme = autoReturn.isChecked, keyboardHeightDp = keyboardHeightDp))
    private fun renderModels() {
        selectedLabel.text = "当前：${selected.label}"
        val state = ModelDownloads.state
        for ((model, row) in rows) {
            val installed = repository.isInstalled(model)
            val (progress, download, use, delete) = row
            val active = state.model == model
            progress.text = when {
                installed -> "已下载"
                active && state.running -> "${state.message} · ${(state.bytes * 100 / state.total.coerceAtLeast(1))}%"
                active -> state.message
                else -> "约 ${repository.spec(model).size / 1024 / 1024} MB"
            }
            download.text = if (active && state.running) "暂停" else "下载"
            download.isEnabled = !deleting && !installed && (!state.running || active)
            download.alpha = if (download.isEnabled) 1f else 0.45f
            use.text = if (selected == model) "正在使用" else "使用"
            use.isEnabled = !deleting && installed && selected != model
            use.alpha = if (use.isEnabled) 1f else 0.4f
            delete.isEnabled = !deleting && !state.running && (repository.directory(model).exists() || repository.stagingDirectory(model).exists())
            delete.alpha = if (delete.isEnabled) 1f else 0.35f
        }
    }
    private fun confirmDelete(model: AsrModel) {
        if (deleting || ModelDownloads.state.running) return
        AlertDialog.Builder(this)
            .setTitle("删除${model.label}？")
            .setMessage("删除本机语音包和未完成的下载，可随时重新下载。" +
                if (selected == model) "当前选择的语音包删除后，需要重新下载或选择其他语音包。" else "")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                deleting = true
                renderModels()
                SpeechEngine.deleteModel(this, model) { result ->
                    deleting = false
                    if (result.isSuccess) ModelDownloads.clearDeletedState(model)
                    preparedModel = null
                    if (!isFinishing && !isDestroyed) {
                        renderModels()
                        android.widget.Toast.makeText(this,
                            if (result.isSuccess) "已删除" else "删除未完成，请重试",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }
    private fun description(model: AsrModel) = when (model) {
        AsrModel.SENSE_VOICE -> "推荐用于日常中文和中英混合输入。"
        AsrModel.PARAFORMER_STREAMING -> "边说边显示文字，松手后自动补标点。支持中英混说，单次最多 60 秒。"
        AsrModel.PARAFORMER -> "中英混合输入的另一选择，可对比常用词的识别效果。"
    }
    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        setPadding(0, dp(5), 0, dp(7))
    }
    private fun section(value: String) = label(value, 20f, VoiceStyle.text).apply { setPadding(0, dp(26), 0, dp(8)) }
    private fun action(value: String, action: () -> Unit) = VoiceStyle.button(this, value).apply {
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) }
    }
    private fun dp(value: Int) = VoiceStyle.dp(this, value)
}
