package com.example.localvoiceime

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var store: VoiceSettingsStore
    private lateinit var autoStartSwitch: Switch
    private lateinit var vadSwitch: Switch
    private lateinit var silenceSpinner: Spinner
    private lateinit var sensitivitySpinner: Spinner
    private lateinit var maxDurationSpinner: Spinner
    private lateinit var autoReturnSwitch: Switch

    private val silenceValues = floatArrayOf(0.6f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f)
    private val thresholdValues = floatArrayOf(0.6f, 0.5f, 0.35f)
    private val maxDurationValues = intArrayOf(15, 30, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        store = VoiceSettingsStore(this)
        setContentView(createContentView())
        showSettings(store.load())
        installChangeListeners()
    }

    private fun createContentView(): ScrollView {
        val padding = (20 * resources.displayMetrics.density).toInt()
        return ScrollView(this).apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, padding)

                    addView(sectionTitle(getString(R.string.settings_recording_section)))
                    autoStartSwitch = settingSwitch(getString(R.string.settings_auto_start))
                    addView(autoStartSwitch)
                    addView(fieldLabel(getString(R.string.settings_max_duration)))
                    maxDurationSpinner = settingSpinner(
                        maxDurationValues.map { getString(R.string.settings_seconds, it.toFloat()) },
                    )
                    addView(maxDurationSpinner)

                    addView(sectionTitle(getString(R.string.settings_vad_section)))
                    vadSwitch = settingSwitch(getString(R.string.settings_vad_enabled))
                    addView(vadSwitch)
                    addView(fieldLabel(getString(R.string.settings_silence_duration)))
                    silenceSpinner = settingSpinner(
                        silenceValues.map { getString(R.string.settings_seconds, it) },
                    )
                    addView(silenceSpinner)
                    addView(fieldLabel(getString(R.string.settings_vad_sensitivity)))
                    sensitivitySpinner = settingSpinner(
                        listOf(
                            getString(R.string.settings_sensitivity_low),
                            getString(R.string.settings_sensitivity_medium),
                            getString(R.string.settings_sensitivity_high),
                        ),
                    )
                    addView(sensitivitySpinner)

                    addView(sectionTitle(getString(R.string.settings_input_section)))
                    autoReturnSwitch = settingSwitch(getString(R.string.settings_auto_return))
                    addView(autoReturnSwitch)

                    addView(sectionTitle(getString(R.string.settings_model_section)))
                    addView(
                        TextView(context).apply {
                            text = getString(R.string.settings_model_info)
                            textSize = 15f
                        },
                    )
                },
            )
        }
    }

    private fun showSettings(settings: VoiceSettings) {
        autoStartSwitch.isChecked = settings.autoStartRecording
        vadSwitch.isChecked = settings.vadEnabled
        silenceSpinner.setSelection(nearestIndex(silenceValues, settings.silenceDurationSeconds))
        sensitivitySpinner.setSelection(nearestIndex(thresholdValues, settings.vadThreshold))
        maxDurationSpinner.setSelection(
            maxDurationValues.indices.minBy { index ->
                kotlin.math.abs(maxDurationValues[index] - settings.maxRecordingSeconds)
            },
        )
        autoReturnSwitch.isChecked = settings.autoReturnToPreviousIme
        setVadControlsEnabled(settings.vadEnabled)
    }

    private fun installChangeListeners() {
        val save = { _: Any? -> saveSettings() }
        autoStartSwitch.setOnCheckedChangeListener { _, _ -> save(null) }
        vadSwitch.setOnCheckedChangeListener { _, enabled ->
            setVadControlsEnabled(enabled)
            save(null)
        }
        autoReturnSwitch.setOnCheckedChangeListener { _, _ -> save(null) }
        silenceSpinner.onItemSelectedListener = SimpleItemSelectedListener { save(null) }
        sensitivitySpinner.onItemSelectedListener = SimpleItemSelectedListener { save(null) }
        maxDurationSpinner.onItemSelectedListener = SimpleItemSelectedListener { save(null) }
    }

    private fun saveSettings() {
        store.save(
            VoiceSettings(
                autoStartRecording = autoStartSwitch.isChecked,
                vadEnabled = vadSwitch.isChecked,
                silenceDurationSeconds = silenceValues[silenceSpinner.selectedItemPosition],
                vadThreshold = thresholdValues[sensitivitySpinner.selectedItemPosition],
                maxRecordingSeconds = maxDurationValues[maxDurationSpinner.selectedItemPosition],
                autoReturnToPreviousIme = autoReturnSwitch.isChecked,
            ),
        )
    }

    private fun setVadControlsEnabled(enabled: Boolean) {
        silenceSpinner.isEnabled = enabled
        sensitivitySpinner.isEnabled = enabled
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        gravity = Gravity.START
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 8)
    }

    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, 12, 0, 4)
    }

    private fun settingSwitch(text: String) = Switch(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, 10, 0, 10)
    }

    private fun settingSpinner(labels: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@SettingsActivity,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
    }

    private fun nearestIndex(values: FloatArray, target: Float): Int =
        values.indices.minBy { index -> kotlin.math.abs(values[index] - target) }
}
