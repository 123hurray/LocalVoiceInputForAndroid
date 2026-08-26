package com.example.localvoiceime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.max

class VoiceInputService : InputMethodService() {

    private lateinit var statusView: TextView
    private lateinit var modelStatusView: TextView
    private lateinit var recordButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pcmBuffer = PcmSampleBuffer(SAMPLE_RATE * MAX_RECORDING_SECONDS)

    @Volatile
    private var isRecording = false
    private var startedAtMs = 0L
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile
    private var senseVoiceAsr: SenseVoiceAsr? = null
    @Volatile
    private var isModelLoading = false
    @Volatile
    private var isDestroyed = false
    @Volatile
    private var isRecognizing = false

    override fun onCreateInputView(): View {
        val padding = (16 * resources.displayMetrics.density).toInt()

        statusView = TextView(this).apply {
            text = getString(R.string.recording_idle)
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setTypeface(typeface, Typeface.BOLD)
        }

        modelStatusView = TextView(this).apply {
            text = getString(R.string.model_loading)
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }

        recordButton = Button(this).apply {
            text = getString(R.string.start_recording)
            setOnClickListener {
                if (isRecording) {
                    stopRecording(shouldRecognize = true)
                } else if (!isRecognizing) {
                    startRecordingOrRequestPermission()
                }
            }
        }

        val testButton = Button(this).apply {
            text = getString(R.string.test_input)
            setOnClickListener {
                currentInputConnection?.commitText(getString(R.string.test_text), 1)
            }
        }

        val returnButton = Button(this).apply {
            text = getString(R.string.return_to_fcitx)
            setOnClickListener { returnToPreviousInputMethod(showPickerOnFailure = true) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
            addView(modelStatusView, matchWidthWrapHeight())
            addView(statusView, matchWidthWrapHeight())
            addView(recordButton, matchWidthWrapHeight())
            addView(returnButton, matchWidthWrapHeight())
            addView(testButton, matchWidthWrapHeight())
            ensureModelLoaded()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::recordButton.isInitialized && !isRecording) {
            recordButton.text = getString(R.string.start_recording)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRecording(shouldRecognize = false)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        isDestroyed = true
        stopRecording(shouldRecognize = false)
        if (!isRecognizing) {
            senseVoiceAsr?.close()
            senseVoiceAsr = null
        }
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun ensureModelLoaded() {
        if (senseVoiceAsr != null || isModelLoading) return
        isModelLoading = true
        modelStatusView.text = getString(R.string.model_loading)
        thread(name = "LocalVoiceIME-SenseVoice-Init") {
            try {
                val recognizer = SenseVoiceAsr(assets)
                if (isDestroyed) {
                    recognizer.close()
                } else {
                    senseVoiceAsr = recognizer
                    mainHandler.post {
                        if (::modelStatusView.isInitialized) {
                            modelStatusView.text = getString(R.string.model_ready)
                        }
                    }
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    if (::modelStatusView.isInitialized) {
                        modelStatusView.text = getString(
                            R.string.model_error,
                            error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
            } finally {
                isModelLoading = false
            }
        }
    }

    private fun startRecordingOrRequestPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(
                Intent(this, MicrophonePermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            return
        }
        startRecording()
    }

    private fun startRecording() {
        if (isRecording || isRecognizing) return
        if (senseVoiceAsr == null) {
            showRecognitionError(getString(R.string.model_not_ready))
            ensureModelLoaded()
            return
        }

        val minimumBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBufferSize <= 0) {
            showError("无法获得有效缓冲区：$minimumBufferSize")
            return
        }

        val bufferSize = max(minimumBufferSize * 2, SAMPLE_RATE / 5 * BYTES_PER_SAMPLE)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (error: RuntimeException) {
            showError(error.message ?: error.javaClass.simpleName)
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            showError("AudioRecord 初始化失败")
            return
        }

        try {
            recorder.startRecording()
        } catch (error: RuntimeException) {
            recorder.release()
            showError(error.message ?: error.javaClass.simpleName)
            return
        }

        audioRecord = recorder
        pcmBuffer.clear()
        startedAtMs = SystemClock.elapsedRealtime()
        isRecording = true
        recordButton.text = getString(R.string.stop_recording)
        updateRecordingStatus()

        recordingThread = thread(name = "LocalVoiceIME-AudioRecord") {
            val samples = ShortArray(bufferSize / BYTES_PER_SAMPLE)
            while (isRecording) {
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                when {
                    count > 0 -> {
                        val appended = pcmBuffer.append(samples, count)
                        if (appended < count || pcmBuffer.size() >= MAX_BUFFERED_SAMPLES) {
                            isRecording = false
                            mainHandler.post {
                                if (::statusView.isInitialized) {
                                    statusView.text = getString(
                                        R.string.recording_limit_reached,
                                        MAX_RECORDING_SECONDS,
                                    )
                                }
                                stopRecording(shouldRecognize = true)
                            }
                        }
                    }
                    count < 0 && isRecording -> {
                        mainHandler.post { showError("AudioRecord.read 返回 $count") }
                        break
                    }
                }
            }
        }
        mainHandler.postDelayed(statusUpdater, STATUS_UPDATE_INTERVAL_MS)
    }

    private fun stopRecording(shouldRecognize: Boolean) {
        if (!isRecording && audioRecord == null) return

        isRecording = false
        mainHandler.removeCallbacks(statusUpdater)
        val recorder = audioRecord
        audioRecord = null
        try {
            recorder?.stop()
        } catch (_: IllegalStateException) {
            // The recorder may already be stopped after an input-view lifecycle change.
        }
        recordingThread?.let { worker ->
            if (worker !== Thread.currentThread()) worker.join(RECORDING_THREAD_JOIN_MS)
        }
        recordingThread = null
        recorder?.release()

        if (::recordButton.isInitialized) {
            recordButton.text = getString(R.string.start_recording)
        }
        if (::statusView.isInitialized && startedAtMs > 0L) {
            statusView.text = getString(
                R.string.recording_stopped,
                elapsedSeconds(),
                pcmBuffer.size(),
            )
        }

        if (shouldRecognize) {
            recognizeBufferedAudio()
        } else {
            pcmBuffer.clear()
        }
    }

    private fun recognizeBufferedAudio() {
        val recognizer = senseVoiceAsr
        val samples = pcmBuffer.toNormalizedFloatArray()
        pcmBuffer.clear()
        if (recognizer == null) {
            showRecognitionError(getString(R.string.model_not_ready))
            return
        }
        if (samples.isEmpty()) {
            statusView.text = getString(R.string.recognition_empty)
            return
        }

        val targetConnection = currentInputConnection
        isRecognizing = true
        recordButton.isEnabled = false
        statusView.text = getString(
            R.string.recognizing,
            samples.size.toFloat() / SAMPLE_RATE,
        )

        thread(name = "LocalVoiceIME-SenseVoice-Decode") {
            try {
                val text = recognizer.recognize(samples)
                if (!isDestroyed) {
                    mainHandler.post { handleRecognitionResult(text, targetConnection) }
                }
            } catch (error: Throwable) {
                if (!isDestroyed) {
                    mainHandler.post {
                        showRecognitionError(error.message ?: error.javaClass.simpleName)
                    }
                }
            } finally {
                isRecognizing = false
                if (isDestroyed) {
                    recognizer.close()
                    senseVoiceAsr = null
                } else {
                    mainHandler.post {
                        if (::recordButton.isInitialized) recordButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun handleRecognitionResult(text: String, targetConnection: InputConnection?) {
        if (text.isBlank()) {
            statusView.text = getString(R.string.recognition_empty)
            return
        }
        if (targetConnection == null || currentInputConnection !== targetConnection) {
            statusView.text = getString(R.string.input_target_changed, text)
            return
        }
        if (targetConnection.commitText(text, 1)) {
            statusView.text = getString(R.string.recognition_result, text)
            mainHandler.postDelayed(
                { returnToPreviousInputMethod(showPickerOnFailure = false) },
                RETURN_TO_PREVIOUS_IME_DELAY_MS,
            )
        } else {
            statusView.text = getString(R.string.recognition_error, "无法提交识别文字")
        }
    }

    @Suppress("DEPRECATION")
    private fun returnToPreviousInputMethod(showPickerOnFailure: Boolean) {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            val token = window.window?.attributes?.token
            token != null && getSystemService(InputMethodManager::class.java)
                .switchToLastInputMethod(token)
        }

        if (!switched && showPickerOnFailure) {
            if (::statusView.isInitialized) {
                statusView.text = getString(R.string.return_to_previous_failed)
            }
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    private val statusUpdater = object : Runnable {
        override fun run() {
            if (!isRecording) return
            updateRecordingStatus()
            mainHandler.postDelayed(this, STATUS_UPDATE_INTERVAL_MS)
        }
    }

    private fun updateRecordingStatus() {
        if (::statusView.isInitialized) {
            statusView.text = getString(
                R.string.recording_active,
                elapsedSeconds(),
                pcmBuffer.size(),
            )
        }
    }

    private fun showError(message: String) {
        stopRecording(shouldRecognize = false)
        if (::statusView.isInitialized) {
            statusView.text = getString(R.string.recording_error, message)
        }
    }

    private fun showRecognitionError(message: String) {
        if (::statusView.isInitialized) {
            statusView.text = getString(R.string.recognition_error, message)
        }
    }

    private fun elapsedSeconds(): Float =
        (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L) / 1000f

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val STATUS_UPDATE_INTERVAL_MS = 100L
        private const val RECORDING_THREAD_JOIN_MS = 500L
        private const val MAX_RECORDING_SECONDS = 60
        private const val MAX_BUFFERED_SAMPLES = SAMPLE_RATE * MAX_RECORDING_SECONDS
        private const val RETURN_TO_PREVIOUS_IME_DELAY_MS = 200L
    }
}
