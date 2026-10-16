package com.example.localvoiceime

import android.content.Context

data class VoiceSettings(
    val asrModel: AsrModel = AsrModel.SENSE_VOICE,
    val keepResident: Boolean = true,
    val autoReturnToPreviousIme: Boolean = true,
)

class VoiceSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)
    fun load() = VoiceSettings(
        asrModel = AsrModel.fromId(preferences.getString("asr_model", null)),
        keepResident = preferences.getBoolean("keep_resident", true),
        autoReturnToPreviousIme = preferences.getBoolean("auto_return_to_previous_ime", true),
    )
    fun save(settings: VoiceSettings) {
        preferences.edit()
            .putString("asr_model", settings.asrModel.id)
            .putBoolean("keep_resident", settings.keepResident)
            .putBoolean("auto_return_to_previous_ime", settings.autoReturnToPreviousIme)
            .apply()
    }
}
