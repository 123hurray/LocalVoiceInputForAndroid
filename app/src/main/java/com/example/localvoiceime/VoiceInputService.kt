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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class VoiceInputService : InputMethodService() {
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var hold: Button
    private lateinit var bars: VoiceBarsView
    private lateinit var correctionToggle: Button
    private lateinit var speechArea: LinearLayout
    private lateinit var correctionPanel: LinearLayout
    private lateinit var correctionWords: CorrectionSelectionView
    private lateinit var candidateStrip: LinearLayout
    private lateinit var redoButton: Button
    private lateinit var sendButton: Button
    private data class DraftSnapshot(
        val text: String, val range: IntRange, val audio: FloatArray?,
        val target: android.view.inputmethod.InputConnection, val request: Int,
        val undo: List<String>, val alternatives: List<String>, val loaded: Boolean,
    )
    private var spokenEdit: DraftSnapshot? = null
    private var redoRecording = false
    private var candidateRunning = false
    private var candidateEpoch = 0
    private var candidatesLoaded = false
    private var draftText = ""
    private var draftAudio: FloatArray? = null
    private var utteranceAudio: FloatArray? = null
    private var draftTarget: android.view.inputmethod.InputConnection? = null
    private var draftGeneration = 0
    private val otherResults = mutableListOf<String>()
    private val draftUndo = ArrayDeque<String>()
    private lateinit var preview: TextView
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

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        win.setWindowAnimations(0)
    }

    override fun onCreateInputView(): View {
        window.window?.navigationBarColor = VoiceStyle.background
        window.window?.setWindowAnimations(0)
        return LinearLayout(this).apply {
            inputPanel = this
            layoutParams = FrameLayout.LayoutParams(-1, dp(VoiceSettingsStore(context).load().keyboardHeightDp))
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            setBackgroundColor(VoiceStyle.background)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                addView(Button(context).apply {
                    text = "< 返回键盘"; textSize = 14f; isAllCaps = false
                    setTextColor(VoiceStyle.muted)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setPadding(0, 0, 0, 0)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { stopRecording(false); cancelPendingSpeech(); returnToKeyboard(true) }
                }, LinearLayout.LayoutParams(dp(112), dp(48)))
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                correctionToggle = Button(context).apply {
                    textSize = 12f; isAllCaps = false; minWidth = 0
                    setPadding(0, 0, 0, 0)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setOnClickListener {
                        if (recording || recognizing || draftTarget != null) {
                            android.widget.Toast.makeText(this@VoiceInputService, "请先完成或取消当前输入", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            settings = settings.copy(correctionEnabled = !settings.correctionEnabled)
                            VoiceSettingsStore(this@VoiceInputService).save(settings)
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

            // The compact button is only a visual cue. The entire lower panel receives gestures.
            speechArea = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(dp(20), 0, dp(20), dp(8))
                setBackgroundColor(VoiceStyle.background)
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
            speechArea.addView(status)
            speechArea.addView(hint)
            preview = TextView(context).apply {
                textSize = 20f; setTextColor(VoiceStyle.text); gravity = Gravity.TOP or Gravity.START
                setPadding(0, dp(4), 0, dp(10))
                maxLines = Int.MAX_VALUE
                visibility = View.GONE
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            speechArea.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
            bars = VoiceBarsView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            speechArea.addView(bars, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
            addView(speechArea, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(createCorrectionPanel(), LinearLayout.LayoutParams(-1, 0, 1f))
            // A single footer owns the same recording control in every state.
            addView(FrameLayout(context).apply {
                setPadding(dp(12), 0, dp(12), 0)
                hold = VoiceStyle.button(context, "按住说话").apply {
                    textSize = 15f
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downY = event.rawY; cancelGesture = false
                                view.parent.requestDisallowInterceptTouchEvent(true)
                                if (draftTarget != null) startRedo() else startRecordingOrRequestPermission()
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
                        else if (draftTarget != null) startRedo() else startRecordingOrRequestPermission()
                    }
                }
                redoButton = hold
                addView(hold, FrameLayout.LayoutParams(dp(144), dp(52), Gravity.CENTER))
                sendButton = VoiceStyle.button(context, "输入", primary = true).apply {
                    textSize = 14f; minWidth = 0; setPadding(0, 0, 0, 0)
                    visibility = View.INVISIBLE
                    setOnClickListener { confirmCorrection() }
                }
                addView(sendButton, FrameLayout.LayoutParams(dp(64), dp(52), Gravity.END or Gravity.CENTER_VERTICAL))
            }, LinearLayout.LayoutParams(-1, dp(64)))
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        active = false; generation++
        if (::hold.isInitialized) { stopRecording(false); cancelPendingSpeech() }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (recording) stopRecording(false)
        cancelPendingSpeech()
        active = true
        val request = ++generation
        settings = VoiceSettingsStore(this).load()
        renderCorrectionToggle()
        window.window?.setWindowAnimations(0)
        // Keep one stable target height across idle, recording and recognition states.
        val availableDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt() - 80
        val panelHeight = minOf(settings.keyboardHeightDp, availableDp.coerceAtLeast(200))
        inputPanel.layoutParams = inputPanel.layoutParams.apply { height = dp(panelHeight) }
        bars.visibility = if (panelHeight < 280) View.GONE else View.VISIBLE
        ready = false
        canRecord = ModelRepository(this).isInstalled(settings.asrModel)
        if (!canRecord) {
            status.text = "先下载一个语音包"
            hint.text = "点击右上角设置，准备好就能使用"
            hold.isEnabled = true
            return
        }
        status.text = "按住，说出你想写的"
        hint.text = ""
        hold.isEnabled = true
        SpeechEngine.prepare(this, settings.asrModel) { result ->
            if (!destroyed && active && request == generation) {
                ready = result.isSuccess
                // Preparation must never interrupt the hold gesture or overwrite its feedback.
                if (!recording && !recognizing) {
                    status.text = if (ready) "按住，说出你想写的" else "暂时无法使用"
                    hint.text = if (ready) "" else "请到设置中检查语音包"
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
        if (recognizing || recording || !active || draftTarget != null) return
        utteranceAudio = null
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
        cancelGesture = false
        status.visibility = View.GONE
        preview.visibility = View.VISIBLE
        preview.text = ""
        updateRecordingUi()
        val request = generation
        val limit = settings.asrModel.recordingLimitSeconds * SAMPLE_RATE
        if (settings.asrModel == AsrModel.PARAFORMER_STREAMING) {
            preview.text = ""
            preview.visibility = View.VISIBLE
            bars.visibility = View.GONE
            val target = currentInputConnection
            lateinit var session: SpeechEngine.StreamingSpeech
            session = SpeechEngine.beginStreaming(this, partial = { text ->
                if (!destroyed && active && request == generation && recording && streamingSpeech === session) {
                    preview.text = text
                    preview.post { preview.layout?.let { layout -> preview.scrollTo(0, max(0, layout.height - preview.height)) } }
                    if (redoRecording) correctionWords.show(spokenEdit?.let { SpokenReplacement.apply(it.text, it.range, text) } ?: text)
                }
            }, final = { result ->
                if (!destroyed && active && request == generation && streamingSpeech === session) {
                    val interruptedEdit = recording && spokenEdit != null
                    if (recording) stopRecording(false)
                    streamingSpeech = null
                    if (interruptedEdit) {
                        android.widget.Toast.makeText(this, "录音中断，已保留原文", android.widget.Toast.LENGTH_SHORT).show()
                    } else completeRecognition(result, target, request)
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
        val wasRedo = redoRecording
        redoRecording = false
        if (wasRedo) redoButton.text = "按住重说"
        main.removeCallbacks(ticker)
        runCatching { audio.stop() }
        recordingThread?.join(500)
        recordingThread = null
        audio.release()
        bars.recording = false; bars.level = 0f; bars.invalidate()
        hold.text = "按住说话"; hold.background = VoiceStyle.rounded(this, VoiceStyle.surface)
        if (!submit) {
            streamingSpeech?.cancel(); streamingSpeech = null
            preview.text = ""; preview.visibility = View.GONE
            pcm.clear(); if (wasRedo) restoreSpokenEdit()
            status.visibility = if (draftTarget == null) View.VISIBLE else View.GONE
            status.text = "已取消"; hint.text = "按住说话 · 松手输入"; return
        }
        if (pcm.size() < SAMPLE_RATE / 4) {
            streamingSpeech?.cancel(); streamingSpeech = null; pcm.clear()
            preview.text = ""; preview.visibility = View.GONE
            if (wasRedo) restoreSpokenEdit()
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
        val target = currentInputConnection
        val request = generation
        pendingSpeech = SpeechEngine.recognize(this, settings.asrModel, samples) { result ->
            completeRecognition(result, target, request)
        }
    }

    private fun completeRecognition(result: Result<String>, target: android.view.inputmethod.InputConnection?, request: Int) {
        if (destroyed || !active || request != generation) return
        val destination = currentInputConnection
        pendingSpeech = null
        recognizing = false
        hold.isEnabled = true
        val text = result.getOrNull().orEmpty().trim()
        val edit = spokenEdit
        if (edit != null) {
            if (result.isSuccess && text.isNotBlank() && edit.request == generation && destination != null) {
                val combined = SpokenReplacement.apply(edit.text, edit.range, text)
                val fullReplacement = edit.range.first == 0 && edit.range.last + 1 == edit.text.length
                spokenEdit = null
                showCorrection(combined, destination, request)
                draftUndo.addAll(edit.undo); draftUndo.addLast(edit.text)
                // A partial recording cannot be used as the whole edited sentence's audio.
                if (!fullReplacement) draftAudio = null
                renderCandidates()
            } else {
                restoreSpokenEdit()
                android.widget.Toast.makeText(this, "没有替换成功，已保留原文", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (preview.visibility == View.VISIBLE) preview.text = text
        if (result.isSuccess && text.isNotBlank() && target != null && destination != null && settings.correctionEnabled) {
            showCorrection(text, destination, request)
            return
        }
        utteranceAudio = null
        if (correctionPanel.visibility == View.VISIBLE) clearCorrection()
        status.visibility = View.VISIBLE
        when {
            result.isFailure -> { status.text = "这次没识别成功"; hint.text = "请再试一次" }
            text.isBlank() -> { status.text = "没有听清，请再说一次"; hint.text = "按住说话 · 松手输入" }
            target == null || destination == null -> {
                status.text = "输入位置已变化"; hint.text = "请在要输入的位置重新说话"
            }
            destination.commitText(text, 1) -> {
                status.text = "已输入"; hint.text = "还可以继续说"
                if (settings.autoReturnToPreviousIme) main.postDelayed({
                    if (!destroyed && active && request == generation && currentInputConnection != null) returnToKeyboard(false)
                }, 150)
            }
            else -> { status.text = "无法输入到这里"; hint.text = "请选择输入框后重试" }
        }
    }

    private fun renderCorrectionToggle() {
        correctionToggle.text = if (settings.correctionEnabled) "纠错 ✓" else "纠错"
        correctionToggle.setTextColor(if (settings.correctionEnabled) VoiceStyle.accent else VoiceStyle.muted)
        correctionToggle.contentDescription = if (settings.correctionEnabled) "纠错已开启，点击关闭" else "纠错已关闭，点击开启"
        correctionToggle.isSelected = settings.correctionEnabled
    }

    private fun createCorrectionPanel() = LinearLayout(this).apply {
        correctionPanel = this
        orientation = LinearLayout.VERTICAL; visibility = View.GONE
        setPadding(dp(12), 0, dp(12), dp(6))
        correctionWords = CorrectionSelectionView(context).apply {
            onSelectionFinished = { if (!recording) requestAlternatives() }
        }
        addView(android.widget.ScrollView(context).apply {
            addView(correctionWords, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        candidateStrip = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidateStrip, FrameLayout.LayoutParams(-2, -1))
        }, LinearLayout.LayoutParams(-1, dp(44)))

    }

    private fun startRedo() {
        if (recording || !active) return
        pendingSpeech?.cancel(); pendingSpeech = null; recognizing = false
        val target = draftTarget ?: return
        val snapshot = DraftSnapshot(draftText,
            correctionWords.selection ?: (0 until draftText.length), draftAudio, target, draftGeneration,
            draftUndo.toList(), otherResults.toList(), candidatesLoaded)
        clearCorrection(restorePanel = false)
        spokenEdit = snapshot
        redoRecording = true
        startRecordingOrRequestPermission()
        if (!recording) { redoRecording = false; restoreSpokenEdit(); return }
        correctionPanel.visibility = View.VISIBLE; speechArea.visibility = View.GONE
        status.visibility = View.GONE; hint.visibility = View.GONE
        correctionWords.isEnabled = false
        correctionWords.show("")
        candidateStrip.removeAllViews()
        redoButton.text = "松手替换"; sendButton.isEnabled = false
    }

    private fun restoreSpokenEdit() {
        val edit = spokenEdit
        spokenEdit = null
        if (edit == null || !active || edit.request != generation || currentInputConnection == null) {
            clearCorrection(); return
        }
        utteranceAudio = edit.audio
        showCorrection(edit.text, edit.target, edit.request)
        draftUndo.addAll(edit.undo)
        otherResults.addAll(edit.alternatives); candidatesLoaded = edit.loaded
        renderCandidates()
    }

    private fun showCorrection(text: String, target: android.view.inputmethod.InputConnection, request: Int) {
        draftText = text; draftAudio = utteranceAudio; utteranceAudio = null
        draftTarget = target; draftGeneration = request; otherResults.clear(); draftUndo.clear()
        candidatesLoaded = false; candidateRunning = false
        correctionWords.isEnabled = true; correctionWords.show(text)
        speechArea.visibility = View.GONE; status.visibility = View.GONE; hint.visibility = View.GONE
        correctionPanel.visibility = View.VISIBLE
        redoButton.text = "按住重说"; sendButton.isEnabled = true; sendButton.visibility = View.VISIBLE
        renderCandidates()
    }

    private fun clearCorrection(restorePanel: Boolean = true) {
        candidateEpoch++; candidateRunning = false; candidatesLoaded = false
        spokenEdit = null
        draftText = ""; draftAudio = null; utteranceAudio = null; draftTarget = null
        otherResults.clear(); draftUndo.clear()
        if (::correctionPanel.isInitialized) {
            correctionWords.show(""); correctionWords.isEnabled = true
            candidateStrip.removeAllViews(); sendButton.isEnabled = true
            if (restorePanel) sendButton.visibility = View.INVISIBLE
            if (restorePanel) {
                correctionPanel.visibility = View.GONE; speechArea.visibility = View.VISIBLE
                preview.text = ""; preview.visibility = View.GONE
                bars.visibility = if (settings.keyboardHeightDp < 280) View.GONE else View.VISIBLE
                status.visibility = View.VISIBLE; hint.visibility = View.VISIBLE
                hint.text = ""
            }
        }
    }

    private fun replaceSelected(replacement: String) {
        if (recording) return
        val range = correctionWords.selection ?: return
        draftUndo.addLast(draftText)
        draftText = draftText.replaceRange(range.first, range.last + 1, replacement)
        correctionWords.show(draftText); renderCandidates()
    }

    private fun confirmCorrection() {
        if (recording) return
        val target = currentInputConnection
        if (!active || generation != draftGeneration || draftTarget == null || target == null) {
            clearCorrection(); status.text = "输入位置已变化"; return
        }
        pendingSpeech?.cancel(); pendingSpeech = null; recognizing = false
        val text = draftText
        if (text.isBlank()) { clearCorrection(); status.text = "没有要输入的文字"; return }
        if (target.commitText(text, 1)) {
            val request = generation
            clearCorrection(); status.text = "已输入"
            if (settings.autoReturnToPreviousIme) main.postDelayed({
                if (!destroyed && active && request == generation && currentInputConnection != null) returnToKeyboard(false)
            }, 150)
        } else android.widget.Toast.makeText(this, "暂时无法输入，请重试", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun renderCandidates() {
        candidateStrip.removeAllViews()
        val range = correctionWords.selection
        if (draftUndo.isNotEmpty()) candidateStrip.addView(Button(this).apply {
            text = "撤销"; textSize = 12f; minWidth = 0
            setTextColor(VoiceStyle.muted); setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener {
                if (!recording && draftUndo.isNotEmpty()) {
                    draftText = draftUndo.removeLast()
                    // Undo may restore a draft from an earlier recording; do not align new audio to it.
                    pendingSpeech?.cancel(); pendingSpeech = null
                    candidateEpoch++; candidateRunning = false; candidatesLoaded = false
                    draftAudio = null; otherResults.clear()
                    correctionWords.show(draftText); renderCandidates()
                }
            }
        }, LinearLayout.LayoutParams(-2, dp(40)))
        if (range == null) return
        val selected = correctionWords.selectedText()
        val options = linkedSetOf<String>()
        otherResults.forEach { result ->
            CorrectionAlignment.alternative(draftText, result, range.first, range.last + 1)?.let {
                if (it != selected && it.isNotEmpty()) options.add(it)
            }
        }
        for (option in options) candidateStrip.addView(VoiceStyle.button(this, option).apply {
            minWidth = 0; textSize = 15f; setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { replaceSelected(option) }
        }, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(6) })
        candidateStrip.addView(Button(this).apply {
            text = "删除"; textSize = 12f; minWidth = 0; setPadding(dp(12), 0, dp(12), 0)
            setTextColor(VoiceStyle.muted); setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { replaceSelected("") }
        }, LinearLayout.LayoutParams(-2, dp(40)))

    }

    private fun requestAlternatives() {
        renderCandidates()
        if (candidateRunning || candidatesLoaded || recording || correctionWords.selection == null) return
        val audio = draftAudio ?: return
        val repository = ModelRepository(this)
        val models = AsrModel.entries.filter { it != settings.asrModel && repository.isInstalled(it) }
        if (models.isEmpty()) return
        val epoch = ++candidateEpoch
        val request = generation
        candidateRunning = true
        renderCandidates()
        fun next(index: Int) {
            if (!active || epoch != candidateEpoch || request != generation || draftTarget == null) return
            if (index == models.size) {
                pendingSpeech = null; candidateRunning = false; candidatesLoaded = true
                renderCandidates(); return
            }
            pendingSpeech = SpeechEngine.recognize(this, models[index], audio) { result ->
                if (!active || epoch != candidateEpoch || request != generation || draftTarget == null) return@recognize
                ready = false
                result.getOrNull()?.takeIf { it.isNotBlank() }?.let { if (it !in otherResults) otherResults.add(it) }
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
        hold.background = VoiceStyle.rounded(this, if (cancelGesture) VoiceStyle.danger else VoiceStyle.accent)
        if (redoRecording) redoButton.text = if (cancelGesture) "松手取消" else "松手替换 · ${seconds}s"
        bars.recording = true; bars.level = level; bars.cancelling = cancelGesture; bars.invalidate()
    }
    private fun cancelPendingSpeech() {
        redoRecording = false
        clearCorrection()
        streamingSpeech?.cancel(); streamingSpeech = null
        if (::preview.isInitialized) { preview.text = ""; preview.visibility = View.GONE }
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
