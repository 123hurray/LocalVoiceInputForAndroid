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
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.max

class VoiceInputService : InputMethodService() {

    private lateinit var statusView: TextView
    private lateinit var modelStatusView: TextView
    private lateinit var recordButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sampleCount = AtomicLong(0)

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
                if (isRecording) stopRecording() else startRecordingOrRequestPermission()
            }
        }

        val testButton = Button(this).apply {
            text = getString(R.string.test_input)
            setOnClickListener {
                currentInputConnection?.commitText(getString(R.string.test_text), 1)
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
            addView(modelStatusView, matchWidthWrapHeight())
            addView(statusView, matchWidthWrapHeight())
            addView(recordButton, matchWidthWrapHeight())
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
        stopRecording()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        isDestroyed = true
        stopRecording()
        senseVoiceAsr?.close()
        senseVoiceAsr = null
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
        if (isRecording) return

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
        sampleCount.set(0)
        startedAtMs = SystemClock.elapsedRealtime()
        isRecording = true
        recordButton.text = getString(R.string.stop_recording)
        updateRecordingStatus()

        recordingThread = thread(name = "LocalVoiceIME-AudioRecord") {
            val samples = ShortArray(bufferSize / BYTES_PER_SAMPLE)
            while (isRecording) {
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                when {
                    count > 0 -> sampleCount.addAndGet(count.toLong())
                    count < 0 && isRecording -> {
                        mainHandler.post { showError("AudioRecord.read 返回 $count") }
                        break
                    }
                }
            }
        }
        mainHandler.postDelayed(statusUpdater, STATUS_UPDATE_INTERVAL_MS)
    }

    private fun stopRecording() {
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
                sampleCount.get(),
            )
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
                sampleCount.get(),
            )
        }
    }

    private fun showError(message: String) {
        stopRecording()
        if (::statusView.isInitialized) {
            statusView.text = getString(R.string.recording_error, message)
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
    }
}
