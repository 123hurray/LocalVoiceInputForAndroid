package com.example.localvoiceime

import android.content.Context

data class VoiceSettings(
    val autoStartRecording: Boolean = true,
    val vadEnabled: Boolean = true,
    val silenceDurationSeconds: Float = 1.2f,
    val vadThreshold: Float = 0.5f,
    val maxRecordingSeconds: Int = 60,
    val autoReturnToPreviousIme: Boolean = true,
)

class VoiceSettingsStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): VoiceSettings = VoiceSettings(
        autoStartRecording = preferences.getBoolean(KEY_AUTO_START, true),
        vadEnabled = preferences.getBoolean(KEY_VAD_ENABLED, true),
        silenceDurationSeconds = preferences.getFloat(KEY_SILENCE_DURATION, 1.2f),
        vadThreshold = preferences.getFloat(KEY_VAD_THRESHOLD, 0.5f),
        maxRecordingSeconds = preferences.getInt(KEY_MAX_RECORDING_SECONDS, 60),
        autoReturnToPreviousIme = preferences.getBoolean(KEY_AUTO_RETURN, true),
    )

    fun save(settings: VoiceSettings) {
        preferences.edit()
            .putBoolean(KEY_AUTO_START, settings.autoStartRecording)
            .putBoolean(KEY_VAD_ENABLED, settings.vadEnabled)
            .putFloat(KEY_SILENCE_DURATION, settings.silenceDurationSeconds)
            .putFloat(KEY_VAD_THRESHOLD, settings.vadThreshold)
            .putInt(KEY_MAX_RECORDING_SECONDS, settings.maxRecordingSeconds)
            .putBoolean(KEY_AUTO_RETURN, settings.autoReturnToPreviousIme)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "voice_settings"
        private const val KEY_AUTO_START = "auto_start_recording"
        private const val KEY_VAD_ENABLED = "vad_enabled"
        private const val KEY_SILENCE_DURATION = "silence_duration_seconds"
        private const val KEY_VAD_THRESHOLD = "vad_threshold"
        private const val KEY_MAX_RECORDING_SECONDS = "max_recording_seconds"
        private const val KEY_AUTO_RETURN = "auto_return_to_previous_ime"
    }
}
