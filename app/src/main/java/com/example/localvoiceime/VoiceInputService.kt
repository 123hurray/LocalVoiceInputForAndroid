package com.example.localvoiceime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class VoiceInputService : InputMethodService() {
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var hold: Button
    private lateinit var bars: VoiceBarsView
    private val main = Handler(Looper.getMainLooper())
    private val pcm = PcmSampleBuffer(SAMPLE_RATE * 60)
    private var settings = VoiceSettings()
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var recording = false
    @Volatile private var level = 0f
    private var recognizing = false
    private var ready = false
    private var canRecord = false
    private var pendingSpeech: PendingSpeech? = null
    private var active = false
    private var destroyed = false
    private var generation = 0
    private var downY = 0f
    private var cancelGesture = false
    private var startedAt = 0L

    override fun onCreateInputView(): View {
        window.window?.navigationBarColor = VoiceStyle.background
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(18))
            setBackgroundColor(VoiceStyle.background)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "本地语音"; textSize = 16f; setTextColor(VoiceStyle.text)
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(VoiceStyle.button(context, "设置").apply {
                    textSize = 14f
                    setOnClickListener { openSettings() }
                }, LinearLayout.LayoutParams(dp(76), dp(44)))
            })
            status = TextView(context).apply {
                text = "准备中…"; textSize = 22f; gravity = Gravity.CENTER
                setTextColor(VoiceStyle.text); setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(20), 0, dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(status)
            bars = VoiceBarsView(context)
            addView(bars, LinearLayout.LayoutParams(-1, dp(56)))
            hint = TextView(context).apply {
                text = "语音只在本机处理"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(VoiceStyle.muted)
                setPadding(0, 0, 0, dp(16))
            }
            addView(hint)
            hold = VoiceStyle.button(context, "按住说话", primary = true).apply {
                textSize = 18f
                contentDescription = "按住说话，松手输入，上滑取消。无障碍操作可点按开始，再点按结束。"
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (!recognizing) {
                                view.parent.requestDisallowInterceptTouchEvent(true)
                                downY = event.rawY; cancelGesture = false
                                startRecordingOrRequestPermission()
                            }
                        }
                        MotionEvent.ACTION_MOVE -> if (recording) {
                            cancelGesture = downY - event.rawY > dp(64)
                            updateRecordingUi()
                        }
                        MotionEvent.ACTION_UP -> {
                            view.parent.requestDisallowInterceptTouchEvent(false)
                            if (recording) stopRecording(!cancelGesture)
                        }
                        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> stopRecording(false)
                    }
                    true
                }
                // Accessibility ACTION_CLICK bypasses touch; offer a start/stop equivalent.
                setOnClickListener { if (recording) stopRecording(true) else startRecordingOrRequestPermission() }
            }
            addView(hold, LinearLayout.LayoutParams(-1, dp(76)))
            addView(VoiceStyle.button(context, "返回键盘").apply {
                setOnClickListener { stopRecording(false); cancelPendingSpeech(); returnToKeyboard(true) }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (recording) stopRecording(false)
        cancelPendingSpeech()
        active = true
        val request = ++generation
        settings = VoiceSettingsStore(this).load()
        ready = false
        canRecord = ModelRepository(this).isInstalled(settings.asrModel)
        if (!canRecord) {
            status.text = "先下载一个语音包"
            hint.text = "点击右上角设置，准备好就能使用"
            hold.isEnabled = true
            return
        }
        status.text = "按住，说出你想写的"
        hint.text = "松手输入 · 上滑取消"
        hold.isEnabled = true
        if (settings.keepResident) ReadyService.start(this)
        SpeechEngine.prepare(this, settings.asrModel) { result ->
            if (!destroyed && active && request == generation) {
                ready = result.isSuccess
                // Preparation must never interrupt the hold gesture or overwrite its feedback.
                if (!recording && !recognizing) {
                    status.text = if (ready) "按住，说出你想写的" else "暂时无法使用"
                    hint.text = if (ready) "松手输入 · 上滑取消" else "请到设置中检查语音包"
                } else if (recognizing && ready) {
                    status.text = "正在识别…"
                    hint.text = "稍等片刻"
                }
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        active = false; generation++
        stopRecording(false)
        cancelPendingSpeech()
        super.onFinishInputView(finishingInput)
    }
    override fun onDestroy() {
        destroyed = true; active = false; generation++
        stopRecording(false)
        cancelPendingSpeech()
        main.removeCallbacksAndMessages(null)
        if (!VoiceSettingsStore(this).load().keepResident) SpeechEngine.release()
        super.onDestroy()
    }

    private fun startRecordingOrRequestPermission() {
        if (recognizing || recording || !active) return
        if (!canRecord) { status.text = "请先在设置中下载语音包"; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MicrophonePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { status.text = "麦克风暂时不可用，请重试"; return }
        val audio = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min * 2, 4096))
        } catch (_: Exception) { status.text = "麦克风暂时不可用，请重试"; return }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release(); status.text = "麦克风暂时不可用，请重试"; return
        }
        try { audio.startRecording() } catch (_: Exception) {
            audio.release(); status.text = "无法录音，请检查麦克风权限"; return
        }
        recorder = audio; pcm.clear(); recording = true
        startedAt = SystemClock.elapsedRealtime(); cancelGesture = false
        updateRecordingUi()
        val request = generation
        val limit = settings.asrModel.recordingLimitSeconds * SAMPLE_RATE
        recordingThread = thread(name = "Voice-Record") {
            val buffer = ShortArray(512)
            while (recording) {
                val n = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n > 0 && recording) {
                    val allowed = minOf(n, (limit - pcm.size()).coerceAtLeast(0))
                    pcm.append(buffer, allowed)
                    level = (buffer.take(n).maxOf { abs(it.toInt()) } / 10000f).coerceIn(0.1f, 1f)
                    if (pcm.size() >= limit) {
                        main.post { if (active && request == generation) stopRecording(!cancelGesture) }
                        break
                    }
                } else if (n < 0 && recording) {
                    main.post {
                        if (active && request == generation) { stopRecording(false); status.text = "录音中断，请重试" }
                    }
                    break
                }
            }
        }
        main.post(ticker)
    }

    private fun stopRecording(submit: Boolean) {
        val audio = recorder ?: return
        recorder = null; recording = false
        main.removeCallbacks(ticker)
        runCatching { audio.stop() }
        recordingThread?.join(500)
        recordingThread = null
        audio.release()
        bars.recording = false; bars.level = 0f; bars.invalidate()
        hold.text = "按住说话"; hold.background = VoiceStyle.rounded(this, VoiceStyle.accent)
        if (!submit) {
            pcm.clear(); status.text = "已取消"; hint.text = "按住说话 · 松手输入"; return
        }
        val samples = pcm.toNormalizedFloatArray(); pcm.clear()
        if (samples.size < SAMPLE_RATE / 4) { status.text = "说话时间太短，请重试"; return }
        recognizing = true; hold.isEnabled = false
        status.text = if (ready) "正在识别…" else "正在处理…"
        hint.text = if (ready) "稍等片刻" else "正在准备，很快就好"
        val target = currentInputConnection
        val request = generation
        pendingSpeech = SpeechEngine.recognize(this, settings.asrModel, samples) { result ->
            if (destroyed || !active || request != generation) return@recognize
            pendingSpeech = null
            recognizing = false
            hold.isEnabled = true
            val text = result.getOrNull().orEmpty().trim()
            when {
                result.isFailure -> { status.text = "这次没识别成功"; hint.text = "请再试一次" }
                text.isBlank() -> { status.text = "没有听清，请再说一次"; hint.text = "按住说话 · 松手输入" }
                target == null || currentInputConnection !== target -> {
                    status.text = "输入位置已变化"; hint.text = "请在要输入的位置重新说话"
                }
                target.commitText(text, 1) -> {
                    status.text = "已输入"; hint.text = "还可以继续说"
                    if (settings.autoReturnToPreviousIme) main.postDelayed({
                        if (!destroyed && active && request == generation && currentInputConnection === target) returnToKeyboard(false)
                    }, 150)
                }
                else -> { status.text = "无法输入到这里"; hint.text = "请选择输入框后重试" }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            updateRecordingUi()
            main.postDelayed(this, 80)
        }
    }
    private fun updateRecordingUi() {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        status.text = if (cancelGesture) "松手取消" else "正在听你说…"
        hint.text = if (cancelGesture) "移回按钮，继续说话" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}  ·  上滑取消"
        hold.text = if (cancelGesture) "松手取消" else "松手输入"
        hold.background = VoiceStyle.rounded(this, if (cancelGesture) VoiceStyle.danger else VoiceStyle.accent)
        bars.recording = true; bars.level = level; bars.cancelling = cancelGesture; bars.invalidate()
    }
    private fun cancelPendingSpeech() {
        pendingSpeech?.cancel()
        pendingSpeech = null
        recognizing = false
        if (::hold.isInitialized) hold.isEnabled = true
    }
    private fun openSettings() {
        stopRecording(false)
        cancelPendingSpeech()
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    @Suppress("DEPRECATION")
    private fun returnToKeyboard(showPicker: Boolean) {
        val switched = if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod() else {
            val token = window.window?.attributes?.token
            token != null && getSystemService(InputMethodManager::class.java).switchToLastInputMethod(token)
        }
        if (!switched && showPicker) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }
    private fun dp(value: Int) = VoiceStyle.dp(this, value)
    companion object { private const val SAMPLE_RATE = 16_000 }
}
