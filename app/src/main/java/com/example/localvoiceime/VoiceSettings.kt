package com.example.localvoiceime

import android.content.Context

data class VoiceSettings(
    val asrModel: AsrModel = AsrModel.SENSE_VOICE,
    val keepResident: Boolean = true,
    val autoReturnToPreviousIme: Boolean = true,
    val keyboardHeightDp: Int = 320,
    val polishEnabled: Boolean = false,
    val correctionEnabled: Boolean = false,
)

class VoiceSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)
    fun load() = VoiceSettings(
        polishEnabled = preferences.getBoolean("polish_enabled", false),
        correctionEnabled = preferences.getBoolean("correction_enabled", false),
        asrModel = AsrModel.fromId(preferences.getString("asr_model", null)),
        keepResident = preferences.getBoolean("keep_resident", true),
        autoReturnToPreviousIme = preferences.getBoolean("auto_return_to_previous_ime", true),
        keyboardHeightDp = preferences.getInt("keyboard_height_dp", 320).coerceIn(200, 500),
    )
    fun save(settings: VoiceSettings) {
        preferences.edit()
            .putBoolean("polish_enabled", settings.polishEnabled)
            .putBoolean("correction_enabled", settings.correctionEnabled)
            .putString("asr_model", settings.asrModel.id)
            .putBoolean("keep_resident", settings.keepResident)
            .putBoolean("auto_return_to_previous_ime", settings.autoReturnToPreviousIme)
            .putInt("keyboard_height_dp", settings.keyboardHeightDp.coerceIn(200, 500))
            .apply()
    }
}
