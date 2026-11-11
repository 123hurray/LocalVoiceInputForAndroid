package com.example.localvoiceime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ScrollView
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/** Shared IME/HID speech surface. Only the text destination and top-left control vary. */
internal class VoiceInputPanel(private val host: Host) {
    interface Host {
        val context: Context
        val destination: Any?
        val editorInfo: EditorInfo? get() = null
        val isIme: Boolean get() = false
        fun submit(target: Any, text: String, done: (InputDelivery) -> Unit)
        fun editKey(newline: Boolean)
        val pointerControls: HidPointerControls? get() = null
        fun openSettings()
        fun openModelSettings()
        fun returnToKeyboard(showPicker: Boolean) {}
    }

    private val context get() = host.context
    private val resources get() = context.resources
    private var submitting = false
    private lateinit var connectionLabel: TextView

    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var hold: Button
    private lateinit var bars: VoiceBarsView
    private lateinit var correctionToggle: Button
    private lateinit var speechArea: LinearLayout
    private lateinit var correctionPanel: LinearLayout
    private lateinit var correctionWords: CorrectionSelectionView
    private lateinit var candidateStatus: TextView
    private var candidateFailures = 0
    private lateinit var candidateStrip: LinearLayout
    private lateinit var enterButton: ImageButton
    private var macFeedback = ""
    private var macFeedbackSource = ""
    private var macFeedbackSelection: IntRange? = null
    private lateinit var sendButton: Button
    private var candidateRunning = false
    private var candidateEpoch = 0
    private var candidatesLoaded = false
    private var macEpoch = 0
    private var macChecking = false
    private lateinit var checkWords: Button
    private var draftText = ""
    private var draftAudio: FloatArray? = null
    private var utteranceAudio: FloatArray? = null
    private var draftTarget: Any? = null
    private var draftGeneration = 0
    private val otherResults = mutableListOf<String>()
    private val draftUndo = ArrayDeque<String>()
    private lateinit var preview: TextView
    private lateinit var previewBox: View
    private var previewScroll: ScrollView? = null
    private var dismissPreview: Button? = null
    private var streamingSpeech: SpeechEngine.StreamingSpeech? = null
    private lateinit var inputPanel: LinearLayout
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

    fun createView(): View {
        return LinearLayout(context).apply {
            inputPanel = this
            layoutParams = FrameLayout.LayoutParams(-1, if (host.isIme) dp(VoiceSettingsStore(context).load().keyboardHeightDp) else -1)
            orientation = LinearLayout.VERTICAL
            if (!host.isIme) isMotionEventSplittingEnabled = true
            setPadding(0, dp(8), 0, 0)
            setBackgroundColor(VoiceStyle.background)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                if (host.isIme) {
                    addView(Button(context).apply {
                        text = "< 返回键盘"; textSize = 14f; isAllCaps = false
                        setTextColor(VoiceStyle.muted)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setPadding(0, 0, 0, 0)
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setOnClickListener { deactivate(); host.returnToKeyboard(true) }
                    }, LinearLayout.LayoutParams(dp(112), dp(48)))
                } else {
                    connectionLabel = TextView(context).apply {
                        text = "未连接"; textSize = 14f; gravity = Gravity.CENTER_VERTICAL
                        setTextColor(VoiceStyle.muted)
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    }
                    addView(connectionLabel, LinearLayout.LayoutParams(dp(168), dp(48)))
                }
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                correctionToggle = Button(context).apply {
                    textSize = 12f; isAllCaps = false; minWidth = 0
                    setPadding(0, 0, 0, 0)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setOnClickListener {
                        if (recording || recognizing || submitting || draftTarget != null) {
                            android.widget.Toast.makeText(this@VoiceInputPanel.context, "请先完成或取消当前输入", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            settings = settings.copy(correctionEnabled = !settings.correctionEnabled)
                            VoiceSettingsStore(this@VoiceInputPanel.context).save(settings)
                            renderCorrectionToggle()
                        }
                    }
                }
                addView(correctionToggle, LinearLayout.LayoutParams(dp(64), dp(48)))
                renderCorrectionToggle()
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_settings)
                    imageTintList = android.content.res.ColorStateList.valueOf(VoiceStyle.muted)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setPadding(dp(13), dp(13), dp(13), dp(13))
                    contentDescription = "设置"
                    setOnClickListener { openSettings() }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
            status = TextView(context).apply {
                text = "按住，说出你想写的"; textSize = 22f; gravity = Gravity.CENTER
                setTextColor(VoiceStyle.text); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(20), dp(20), dp(20), dp(8))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            hint = TextView(context).apply {
                text = ""; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(VoiceStyle.muted)
                setPadding(dp(20), 0, dp(20), dp(14))
            }

            // In the IME the whole panel is a voice gesture; the HID surface is a trackpad.
            speechArea = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(dp(20), 0, dp(20), dp(8))
                setBackgroundColor(VoiceStyle.background)
                if (host.isIme) {
                    contentDescription = "按住此区域说话，松手输入，上滑取消。无障碍操作可点按开始，再点按结束。"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
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
                    setOnClickListener { if (recording) stopRecording(true) else startRecordingOrRequestPermission() }
                }
            }
            speechArea.addView(status)
            speechArea.addView(hint)
            if (!host.isIme) {
                val closePreview = Button(context).apply {
                    text = "关闭预览"; textSize = 13f; isAllCaps = false
                    setTextColor(VoiceStyle.muted)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    visibility = View.GONE
                    setOnClickListener {
                        visibility = View.GONE
                        preview.text = ""; previewBox.visibility = View.GONE
                        status.text = "按住，说出你想写的"
                        this@VoiceInputPanel.hint.text = "单指移动指针 · 双指滑动滚动"
                    }
                }
                dismissPreview = closePreview
                speechArea.addView(closePreview, LinearLayout.LayoutParams(-2, dp(44)).apply { gravity = Gravity.END })
            }
            preview = TextView(context).apply {
                textSize = 20f; setTextColor(VoiceStyle.text); gravity = Gravity.TOP or Gravity.START
                setPadding(0, dp(4), 0, dp(10))
                maxLines = Int.MAX_VALUE
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                if (!host.isIme) setTextIsSelectable(false)
            }
            previewBox = if (host.isIme) preview else ScrollView(context).apply {
                isFillViewport = false
                addView(preview, FrameLayout.LayoutParams(-1, -2))
                previewScroll = this
            }
            previewBox.visibility = View.GONE
            speechArea.addView(previewBox, LinearLayout.LayoutParams(-1, 0, 1f))
            bars = VoiceBarsView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            speechArea.addView(bars, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
            addView(speechArea, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(createCorrectionPanel(), LinearLayout.LayoutParams(-1, 0, 1f))
            host.pointerControls?.let { pointer ->
                pointer.bindPad(speechArea) {
                    active && !recording && !recognizing && !submitting &&
                        draftTarget == null && previewBox.visibility != View.VISIBLE
                }
            }
            // A single footer owns the same recording control in every state.
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                hold = VoiceStyle.button(context, "按住说话").apply {
                    textSize = 15f
                    setPadding(dp(8), 0, dp(8), 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downY = event.rawY; cancelGesture = false
                                view.parent.requestDisallowInterceptTouchEvent(true)
                                if (draftTarget == null) startRecordingOrRequestPermission()
                            }
                            MotionEvent.ACTION_MOVE -> if (recording) {
                                cancelGesture = downY - event.rawY > dp(64); updateRecordingUi()
                            }
                            MotionEvent.ACTION_UP -> {
                                view.parent.requestDisallowInterceptTouchEvent(false)
                                if (recording) stopRecording(!cancelGesture)
                            }
                            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> if (recording) stopRecording(false)
                        }
                        true
                    }
                    setOnClickListener {
                        if (recording) stopRecording(true)
                        else if (draftTarget == null) startRecordingOrRequestPermission()
                    }
                }

                sendButton = VoiceStyle.button(context, "输入", primary = true).apply {
                    textSize = 14f; minWidth = 0; setPadding(0, 0, 0, 0)
                    visibility = View.GONE
                    setOnClickListener { confirmCorrection() }
                }
                addView(hold, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
                addView(sendButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
                fun editButton(icon: Int, description: String, newline: Boolean) = ImageButton(context).apply {
                    setImageResource(icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(VoiceStyle.text)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = null
                    contentDescription = description
                    setOnClickListener { editKey(newline) }
                }
                addView(editButton(R.drawable.ic_backspace, "删除", false), LinearLayout.LayoutParams(dp(48), dp(48)))
                enterButton = editButton(R.drawable.ic_newline, "换行", true)
                addView(enterButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            }, LinearLayout.LayoutParams(-1, dp(64)))
        }
    }

    fun activate() {
        if (!::hold.isInitialized || active) return
        if (recording) stopRecording(false)
        cancelPendingSpeech()
        active = true
        val request = ++generation
        settings = VoiceSettingsStore(context).load()
        renderCorrectionToggle()
        renderEnterKey()
        // Keep one stable target height across idle, recording and recognition states.
        val availableDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt() - 80
        val panelHeight = minOf(settings.keyboardHeightDp, availableDp.coerceAtLeast(200))
        inputPanel.layoutParams = inputPanel.layoutParams.apply { height = if (host.isIme) dp(panelHeight) else -1 }
        bars.visibility = if (host.isIme && panelHeight < 280) View.GONE else View.VISIBLE
        ready = false
        if (!host.isIme && host.destination == null) {
            hold.isEnabled = false
            status.text = "未连接"; hint.text = "在右上角设置中连接 Mac"
            return
        }
        canRecord = ModelRepository(context).isInstalled(settings.asrModel)
        if (!canRecord) {
            status.text = "先下载一个语音包"
            hint.text = "点击右上角设置，准备好就能使用"
            hold.isEnabled = true
            return
        }
        status.text = "按住，说出你想写的"
        hint.text = if (host.isIme) "" else "单指移动指针 · 双指滑动滚动"
        hold.isEnabled = true
        SpeechEngine.prepare(context, settings.asrModel) { result ->
            if (!destroyed && active && request == generation) {
                ready = result.isSuccess
                // Preparation must never interrupt the hold gesture or overwrite its feedback.
                if (!recording && !recognizing) {
                    status.text = if (ready) "按住，说出你想写的" else "暂时无法使用"
                    hint.text = if (!ready) "请到设置中检查语音包" else if (host.isIme) "" else "单指移动指针 · 双指滑动滚动"
                } else if (recognizing && ready) {
                    status.text = "正在识别…"
                    hint.text = "稍等片刻"
                }
            }
        }
    }

    fun setConnection(message: String) {
        if (::connectionLabel.isInitialized) connectionLabel.text = message
    }

    fun deactivate() {
        active = false; generation++
        stopRecording(false)
        cancelPendingSpeech()
    }

    fun destroy() {
        destroyed = true
        deactivate()
        main.removeCallbacksAndMessages(null)
        if (!VoiceSettingsStore(context).load().keepResident) SpeechEngine.release()
    }

    private fun startRecordingOrRequestPermission() {
        if (recognizing || recording || submitting || !active || draftTarget != null || host.destination == null) return
        utteranceAudio = null
        if (!canRecord) { status.text = "请先在设置中下载语音包"; return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent(context, MicrophonePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        dismissPreview?.visibility = View.GONE
        host.pointerControls?.discardPointerMotion()
        cancelGesture = false
        status.visibility = View.GONE
        previewBox.visibility = View.VISIBLE
        preview.text = ""
        if (!host.isIme) bars.visibility = View.VISIBLE
        updateRecordingUi()
        val request = generation
        val limit = settings.asrModel.recordingLimitSeconds * SAMPLE_RATE
        if (settings.asrModel == AsrModel.PARAFORMER_STREAMING) {
            preview.text = ""
            previewBox.visibility = View.VISIBLE
            bars.visibility = View.GONE
            val target = host.destination
            lateinit var session: SpeechEngine.StreamingSpeech
            session = SpeechEngine.beginStreaming(context, partial = { text ->
                if (!destroyed && active && request == generation && recording && streamingSpeech === session) {
                    preview.text = text
                    preview.post {
                        val scroll = previewScroll
                        if (scroll != null) scroll.scrollTo(0, max(0, preview.height - scroll.height))
                        else preview.layout?.let { layout -> preview.scrollTo(0, max(0, layout.height - preview.height)) }
                    }
                }
            }, final = { result ->
                if (!destroyed && active && request == generation && streamingSpeech === session) {
                    if (recording) stopRecording(false)
                    streamingSpeech = null
                    completeRecognition(result, target, request)
                }
            })
            streamingSpeech = session
        }
        val liveSession = streamingSpeech
        recordingThread = thread(name = "Voice-Record") {
            val buffer = ShortArray(512)
            while (recording) {
                val n = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n > 0 && recording) {
                    val allowed = minOf(n, (limit - pcm.size()).coerceAtLeast(0))
                    pcm.append(buffer, allowed)
                    liveSession?.accept(buffer, allowed)
                    var peak = 0
                    for (i in 0 until n) peak = max(peak, abs(buffer[i].toInt()))
                    level = (peak / 10000f).coerceIn(0.1f, 1f)
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
        hold.text = "按住说话"; hold.background = VoiceStyle.rounded(context, VoiceStyle.surface)
        if (!submit) {
            streamingSpeech?.cancel(); streamingSpeech = null
            preview.text = ""; previewBox.visibility = View.GONE
            pcm.clear()
            status.visibility = if (draftTarget == null) View.VISIBLE else View.GONE
            status.text = "已取消"; hint.text = "按住说话 · 松手输入"; return
        }
        if (pcm.size() < SAMPLE_RATE / 4) {
            streamingSpeech?.cancel(); streamingSpeech = null; pcm.clear()
            preview.text = ""; previewBox.visibility = View.GONE
            status.visibility = if (draftTarget == null) View.VISIBLE else View.GONE
            status.text = "说话时间太短，请重试"; return
        }
        utteranceAudio = if (settings.correctionEnabled) pcm.toNormalizedFloatArray() else null
        val live = streamingSpeech
        if (live != null) {
            pcm.clear(); recognizing = true; hold.isEnabled = false
            status.text = "正在整理文字…"; hint.text = "稍等片刻"
            live.finish()
            return
        }
        val samples = utteranceAudio ?: pcm.toNormalizedFloatArray(); pcm.clear()
        recognizing = true; hold.isEnabled = false
        status.text = if (ready) "正在识别…" else "正在处理…"
        hint.text = if (ready) "稍等片刻" else "正在准备，很快就好"
        val target = host.destination
        val request = generation
        pendingSpeech = SpeechEngine.recognize(context, settings.asrModel, samples) { result ->
            completeRecognition(result, target, request)
        }
    }

    private fun completeRecognition(result: Result<String>, target: Any?, request: Int) {
        if (destroyed || !active || request != generation) return
        val destination = host.destination
        pendingSpeech = null
        recognizing = false
        hold.isEnabled = true
        val text = result.getOrNull().orEmpty().trim()
        if (!host.isIme && (result.isFailure || text.isBlank() || target == null || destination !== target)) {
            preview.text = ""; previewBox.visibility = View.GONE
        }
        if (previewBox.visibility == View.VISIBLE) preview.text = text
        if (result.isSuccess && text.isNotBlank() && target != null && destination === target && settings.correctionEnabled) {
            showCorrection(text, destination, request)
            return
        }
        utteranceAudio = null
        if (correctionPanel.visibility == View.VISIBLE) clearCorrection()
        status.visibility = View.VISIBLE
        when {
            result.isFailure -> { status.text = "这次没识别成功"; hint.text = "请再试一次" }
            text.isBlank() -> { status.text = "没有听清，请再说一次"; hint.text = "按住说话 · 松手输入" }
            target == null || destination !== target -> {
                status.text = "输入位置已变化"; hint.text = "请重新说话"
            }
            else -> submitText(target, text, request, false)
        }
    }

    fun showDeliveryProgress(message: String) {
        if (!active || !submitting) return
        if (draftTarget != null) candidateStatus.text = message
        else status.text = message
    }

    private fun submitText(target: Any, text: String, request: Int, review: Boolean) {
        submitting = true
        hold.isEnabled = false; sendButton.isEnabled = false
        if (review) {
            correctionWords.isEnabled = false
            pendingSpeech?.cancel(); pendingSpeech = null
            candidateEpoch++; macEpoch++; macChecking = false; candidateRunning = false
            candidateStrip.removeAllViews(); candidateStatus.visibility = View.VISIBLE
            candidateStatus.text = "正在输入…"; candidateStatus.isClickable = false
            checkWords.isEnabled = false
        } else if (!host.isIme) {
            status.text = "正在输入…"; hint.text = ""
        }
        host.submit(target, text) { result ->
            if (!active || destroyed || request != generation || host.destination !== target) return@submit
            submitting = false
            hold.isEnabled = true; sendButton.isEnabled = true; correctionWords.isEnabled = true
            if (result == InputDelivery.CONFIRMED) {
                if (review) clearCorrection()
                status.text = when {
                    host.isIme -> "已输入"
                    else -> "Mac 已确认接收"
                }
                hint.text = "还可以继续说"
                if (!host.isIme) {
                    preview.text = text
                    previewBox.visibility = View.VISIBLE
                    dismissPreview?.visibility = View.VISIBLE
                    bars.visibility = View.GONE
                    previewScroll?.scrollTo(0, 0)
                }
                if (host.isIme && settings.autoReturnToPreviousIme) main.postDelayed({
                    if (!destroyed && active && request == generation && host.destination === target) host.returnToKeyboard(false)
                }, 150)
            } else if (!host.isIme) {
                if (!review) showCorrection(text, target, request) else renderCandidates()
                val message = when (result) {
                    InputDelivery.UNCONFIRMED -> "Mac 未回复，请先确认是否已收到"
                    InputDelivery.CHECKSUM_FAILED -> "校验失败，已重试三次"
                    else -> "输入失败，请重试"
                }
                candidateStatus.visibility = View.VISIBLE
                candidateStatus.isClickable = false
                candidateStatus.text = message
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            } else if (review) {
                renderCandidates()
                android.widget.Toast.makeText(context, "暂时无法输入，请重试", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                status.text = "无法输入到这里"; hint.text = "请选择输入框后重试"
            }
        }
    }

    private fun renderCorrectionToggle() {
        correctionToggle.text = if (settings.correctionEnabled) "纠错 ✓" else "纠错"
        correctionToggle.setTextColor(if (settings.correctionEnabled) VoiceStyle.accent else VoiceStyle.muted)
        correctionToggle.contentDescription = if (settings.correctionEnabled) "纠错已开启，点击关闭" else "纠错已关闭，点击开启"
        correctionToggle.isSelected = settings.correctionEnabled
    }

    private fun createCorrectionPanel() = LinearLayout(context).apply {
        correctionPanel = this
        orientation = LinearLayout.VERTICAL; visibility = View.GONE
        setPadding(dp(4), 0, dp(4), dp(6))
        correctionWords = CorrectionSelectionView(context).apply {
            onSelectionFinished = { if (!recording) requestAlternatives() }
        }
        addView(CorrectionScrollView(context, correctionWords), LinearLayout.LayoutParams(-1, 0, 1f))
        candidateStrip = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            checkWords = Button(context).apply {
                text = "查错词"; textSize = 12f; minWidth = 0
                setTextColor(VoiceStyle.muted); setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { checkDraftWords() }
            }
            addView(checkWords, LinearLayout.LayoutParams(dp(64), -1))
            candidateStatus = TextView(context).apply {
                textSize = 12f; setTextColor(VoiceStyle.muted)
                setPadding(dp(4), 0, dp(4), 0)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setOnClickListener {
                    if (!candidateRunning && candidateFailures > 0) {
                        candidatesLoaded = false; candidateFailures = 0; otherResults.clear()
                        requestAlternatives()
                    }
                }
            }
            addView(candidateStatus, LinearLayout.LayoutParams(dp(108), -1))
            addView(android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false; isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(candidateStrip, FrameLayout.LayoutParams(-2, -1))
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }, LinearLayout.LayoutParams(-1, dp(44)))

    }

    private fun renderEnterKey() {
        if (!::enterButton.isInitialized) return
        val info = host.editorInfo
        val action = if (draftTarget != null) null else EditorAction.resolve(info?.imeOptions ?: EditorInfo.IME_ACTION_NONE, info?.actionLabel?.toString(), info?.actionId ?: 0)
        enterButton.contentDescription = action?.label ?: "换行"
        enterButton.setImageResource(if (action == null) R.drawable.ic_newline else R.drawable.ic_editor_action)
        enterButton.tooltipText = enterButton.contentDescription
    }

    private fun renderPrimaryAction(review: Boolean) {
        renderEnterKey()
        hold.text = "按住说话"
        hold.visibility = if (review) View.GONE else View.VISIBLE
        sendButton.visibility = if (review) View.VISIBLE else View.GONE
        sendButton.isEnabled = true
    }

    private fun showCorrection(text: String, target: Any, request: Int) {
        draftText = text; draftAudio = utteranceAudio; utteranceAudio = null
        draftTarget = target; draftGeneration = request; otherResults.clear(); draftUndo.clear()
        candidatesLoaded = false; candidateRunning = false; candidateFailures = 0
        correctionWords.isEnabled = true; correctionWords.show(text)
        speechArea.visibility = View.GONE; status.visibility = View.GONE; hint.visibility = View.GONE
        correctionPanel.visibility = View.VISIBLE
        renderPrimaryAction(true)
        renderCandidates()
    }

    private fun clearCorrection(restorePanel: Boolean = true) {
        macEpoch++; macChecking = false; macFeedback = ""
        candidateEpoch++; candidateRunning = false; candidatesLoaded = false; candidateFailures = 0
        draftText = ""; draftAudio = null; utteranceAudio = null; draftTarget = null
        otherResults.clear(); draftUndo.clear()
        if (::correctionPanel.isInitialized) {
            correctionWords.show(""); correctionWords.isEnabled = true
            candidateStrip.removeAllViews(); candidateStatus.text = ""; candidateStatus.visibility = View.GONE
            sendButton.isEnabled = true
            if (restorePanel) renderPrimaryAction(false)
            if (restorePanel) {
                correctionPanel.visibility = View.GONE; speechArea.visibility = View.VISIBLE
                preview.text = ""; previewBox.visibility = View.GONE
                dismissPreview?.visibility = View.GONE
                bars.visibility = if (host.isIme && settings.keyboardHeightDp < 280) View.GONE else View.VISIBLE
                status.visibility = View.VISIBLE; hint.visibility = View.VISIBLE
                hint.text = ""
            }
        }
    }

    private fun replaceSelected(replacement: String) {
        if (recording || recognizing || submitting) return
        val range = correctionWords.selection ?: return
        draftUndo.addLast(draftText)
        draftText = draftText.replaceRange(range.first, range.last + 1, replacement)
        correctionWords.show(draftText); renderCandidates()
    }

    private fun confirmCorrection() {
        if (recording || recognizing || submitting) return
        val target = host.destination
        if (!active || generation != draftGeneration || draftTarget == null || target !== draftTarget) {
            clearCorrection(); status.text = "输入位置已变化"; return
        }
        val text = draftText
        if (text.isBlank()) { clearCorrection(); status.text = "没有要输入的文字"; return }
        if (target != null) submitText(target, text, generation, true)
    }

    private fun renderCandidates() {
        renderCandidatesContent()
        if (macChecking || (macFeedback.isNotEmpty() && macFeedbackSource == draftText && macFeedbackSelection == correctionWords.selection)) {
            candidateStatus.text = if (macChecking) "正在查错词…" else macFeedback
            candidateStatus.visibility = View.VISIBLE
            candidateStatus.isClickable = false
        }
    }

    private fun renderCandidatesContent() {
        checkWords.text = if (macChecking) "检查中…" else "查错词"
        checkWords.isEnabled = !macChecking && !recording && !recognizing
        candidateStrip.removeAllViews()
        val range = correctionWords.selection
        if (draftUndo.isNotEmpty()) candidateStrip.addView(Button(context).apply {
            text = "撤销"; textSize = 12f; minWidth = 0
            setTextColor(VoiceStyle.muted); setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener {
                if (!recording && !recognizing && draftUndo.isNotEmpty()) {
                    draftText = draftUndo.removeLast()
                    // Undo may restore a draft from an earlier recording; do not align new audio to it.
                    pendingSpeech?.cancel(); pendingSpeech = null
                    candidateEpoch++; candidateRunning = false; candidatesLoaded = false
                    draftAudio = null; otherResults.clear()
                    correctionWords.show(draftText); renderCandidates()
                }
            }
        }, LinearLayout.LayoutParams(-2, dp(40)))
        if (range == null) { candidateStatus.text = ""; candidateStatus.visibility = View.GONE; return }
        candidateStatus.visibility = View.VISIBLE
        val selected = correctionWords.selectedText()
        val options = linkedSetOf<String>()
        otherResults.forEach { result ->
            CorrectionAlignment.alternative(draftText, result, range.first, range.last + 1)?.let {
                if (it != selected && it.isNotEmpty()) options.add(it)
            }
        }
        val hasModels = AsrModel.entries.any {
            it != settings.asrModel && ModelRepository(context).isInstalled(it)
        }
        candidateStatus.text = CandidateFeedback.text(true, candidateRunning, draftAudio != null,
            hasModels, candidatesLoaded, options.isNotEmpty(), candidateFailures)
        candidateStatus.isClickable = !candidateRunning && candidatesLoaded && candidateFailures > 0
        for (option in options) candidateStrip.addView(VoiceStyle.button(context, option).apply {
            minWidth = 0; textSize = 15f; setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { replaceSelected(option) }
        }, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(6) })
        candidateStrip.addView(Button(context).apply {
            text = "删除"; textSize = 12f; minWidth = 0; setPadding(dp(12), 0, dp(12), 0)
            setTextColor(VoiceStyle.muted); setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { replaceSelected("") }
        }, LinearLayout.LayoutParams(-2, dp(40)))

    }

    private fun checkDraftWords() {
        if (macChecking || recording || recognizing || submitting || draftTarget == null || !active) return
        if (!MacBert.installed(context)) {
            android.widget.Toast.makeText(context, "请先下载错词检查包", android.widget.Toast.LENGTH_SHORT).show()
            host.openModelSettings()
            return
        }
        pendingSpeech?.cancel(); pendingSpeech = null
        candidateEpoch++; candidateRunning = false
        val source = draftText
        val selection = correctionWords.selection
        val request = generation
        val epoch = ++macEpoch
        macFeedback = ""
        val started = android.os.SystemClock.elapsedRealtime()
        macChecking = true; renderCandidates()
        MacBert.check(context, source) { result ->
            if (!active || destroyed || epoch != macEpoch || request != generation || draftTarget == null) return@check
            macChecking = false; renderCandidates()
            if (source != draftText || selection != correctionWords.selection) return@check
            val elapsed = java.lang.String.format(java.util.Locale.ROOT, "%.1f秒", (android.os.SystemClock.elapsedRealtime() - started) / 1000.0)
            fun feedback(message: String) {
                macFeedback = "$message\n$elapsed"; macFeedbackSource = source; macFeedbackSelection = selection
                renderCandidates()
            }
            val corrected = result.getOrNull()
            if (corrected == null) {
                feedback("检查失败，请重试")
                return@check
            }
            val before = if (selection == null) source else source.substring(selection.first, selection.last + 1)
            val after = if (selection == null) corrected else corrected.substring(selection.first, selection.last + 1)
            if (before == after) {
                feedback(if (selection == null) "未发现错词" else "所选文字无修改")
                return@check
            }
            // Offer a candidate; text stays unchanged until the user taps it.
            candidateStatus.text = "检查完成\n$elapsed"; candidateStatus.visibility = View.VISIBLE
            candidateStatus.isClickable = false
            candidateStrip.removeAllViews()
            candidateStrip.addView(VoiceStyle.button(context, after).apply {
                text = "采用：" + before.indices.filter { before[it] != after[it] }.joinToString("、") { "${before[it]}→${after[it]}" }
                contentDescription = "采用错词修改：$after"
                textSize = 15f; minWidth = 0; maxLines = 1
                setOnClickListener {
                    if (!active || generation != request || epoch != macEpoch || draftText != source || recording || recognizing) return@setOnClickListener
                    if (selection != correctionWords.selection) return@setOnClickListener
                    draftUndo.addLast(draftText)
                    draftText = if (selection == null) corrected else source.replaceRange(selection.first, selection.last + 1, after)
                    macEpoch++; macFeedback = ""
                    correctionWords.show(draftText); renderCandidates()
                }
            }, LinearLayout.LayoutParams(-2, dp(40)))
        }
    }

    private fun requestAlternatives() {
        renderCandidates()
        if (candidateRunning || candidatesLoaded || recording || submitting || correctionWords.selection == null) return
        val audio = draftAudio ?: return
        val repository = ModelRepository(context)
        val models = AsrModel.entries.filter { it != settings.asrModel && repository.isInstalled(it) }
        if (models.isEmpty()) return
        val epoch = ++candidateEpoch
        val request = generation
        candidateRunning = true; candidateFailures = 0
        renderCandidates()
        fun next(index: Int) {
            if (!active || epoch != candidateEpoch || request != generation || draftTarget == null) return
            if (index == models.size) {
                pendingSpeech = null; candidateRunning = false; candidatesLoaded = true
                renderCandidates(); return
            }
            pendingSpeech = SpeechEngine.recognize(context, models[index], audio) { result ->
                if (!active || epoch != candidateEpoch || request != generation || draftTarget == null) return@recognize
                ready = false
                val text = result.getOrNull()?.takeIf { it.isNotBlank() }
                if (text == null) candidateFailures++
                else if (text !in otherResults) otherResults.add(text)
                renderCandidates()
                next(index + 1)
            }
        }
        next(0)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            updateRecordingUi()
            main.postDelayed(this, 80)
        }
    }
    private fun updateRecordingUi() {
        // Count accepted PCM, so the timer cannot advance before audio arrives or during a read stall.
        val seconds = pcm.size() / SAMPLE_RATE
        status.text = if (cancelGesture) "松手取消" else "正在听你说…"
        hint.text = if (cancelGesture) "移回按钮，继续说话" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        hold.text = if (cancelGesture) "松手取消" else "松手输入"
        hold.background = VoiceStyle.rounded(context, if (cancelGesture) VoiceStyle.danger else VoiceStyle.accent)
        bars.recording = true; bars.level = level; bars.cancelling = cancelGesture; bars.invalidate()
    }
    private fun editKey(newline: Boolean) {
        if (!active || recording || recognizing || submitting || host.destination == null) return
        if (draftTarget != null) {
            val selected = correctionWords.selection
            if (selected != null) { replaceSelected(if (newline) "\n" else ""); return }
            if (!newline && draftText.isEmpty()) return
            draftUndo.addLast(draftText)
            draftText = if (newline) draftText + "\n" else {
                val end = draftText.offsetByCodePoints(draftText.length, -1)
                draftText.substring(0, end)
            }
            correctionWords.show(draftText); renderCandidates()
            return
        }
        host.editKey(newline)
    }
    private fun cancelPendingSpeech() {
        submitting = false
        clearCorrection()
        streamingSpeech?.cancel(); streamingSpeech = null
        if (::preview.isInitialized) { preview.text = ""; previewBox.visibility = View.GONE }
        pendingSpeech?.cancel()
        pendingSpeech = null
        recognizing = false
        if (::hold.isInitialized) hold.isEnabled = false
    }
    private fun openSettings() {
        if (!submitting) host.openSettings()
    }
    private fun dp(value: Int) = VoiceStyle.dp(context, value)
    companion object { private const val SAMPLE_RATE = 16_000 }
}
