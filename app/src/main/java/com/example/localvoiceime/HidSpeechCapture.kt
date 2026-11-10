package com.example.localvoiceime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executors

/** The capture thread alone owns the recorder and its in-memory PCM buffer. */
internal class HidSpeechCapture(
    private val main: Handler,
    private val limitSeconds: Int,
    private val live: SpeechEngine.StreamingSpeech?,
    private val started: (Long) -> Unit,
    private val completed: (Result<FloatArray>) -> Unit,
) {
    @Volatile private var stopped = false
    @Volatile private var cancelled = false

    fun stop(submit: Boolean) {
        if (!submit) cancelled = true
        stopped = true
    }

    @SuppressLint("MissingPermission") // The activity checks RECORD_AUDIO before starting.
    fun start() {
        worker.execute {
            if (stopped) { main.post { if (!cancelled) completed(Result.success(FloatArray(0))) }; return@execute }
            val result = runCatching {
                val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 4096))
                try {
                    check(audio.state == AudioRecord.STATE_INITIALIZED)
                    val pcm = PcmSampleBuffer(limitSeconds * RATE)
                    val buffer = ShortArray(512)
                    if (!stopped) {
                        audio.startRecording()
                        val start = SystemClock.elapsedRealtime()
                        main.post { if (!cancelled) started(start) }
                    }
                    while (!stopped) {
                        val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        check(count >= 0)
                        if (cancelled) break
                        val accepted = pcm.append(buffer, count)
                        if (accepted > 0) live?.accept(buffer, accepted)
                        if (pcm.size() >= limitSeconds * RATE) break
                    }
                    if (!cancelled && pcm.size() >= RATE / 4) pcm.toNormalizedFloatArray() else FloatArray(0)
                } finally {
                    runCatching { audio.stop() }
                    audio.release()
                }
            }
            main.post { if (!cancelled) completed(result) }
        }
    }

    private companion object {
        const val RATE = 16000
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "HID-Voice-Record") }
    }
}
