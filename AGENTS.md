# AGENTS.md

## Project

LocalVoiceIME is an Android offline voice IME used with Fcitx5 for Android.

Data flow:

`Fcitx5 voice subtype -> AudioRecord -> SenseVoice -> commitText -> Fcitx5`

## Privacy invariants

- Never upload, persist, log, or share microphone audio.
- Do not add `INTERNET` or cloud ASR dependencies without explicit user approval.
- Keep audio at 16 kHz, mono, PCM 16-bit unless the model pipeline changes.
- Stop and release `AudioRecord` when the input view finishes or the service dies.
- Run recording and inference off the main thread.
- Validate the active `InputConnection` before committing delayed results.

## Model assets

- `app/src/main/assets/sense-voice/model.int8.onnx`, `tokens.txt`, and
  `silero_vad_v5.onnx` are local, ignored files. Never add them to Git.
- The model archive URL and SHA-256 are documented in `README.md` and
  `app/src/main/assets/sense-voice/README.md`.
- Current runtime: sherpa-onnx 1.13.4, SenseVoice Small INT8, ARM64 only.

## Development

- Min SDK 26; Gradle Kotlin DSL; Kotlin source.
- Keep `VoiceInputService` protected by `android.permission.BIND_INPUT_METHOD`.
- Permission UI belongs in `MicrophonePermissionActivity`; recording belongs in
  `VoiceInputService`.
- Preserve the Fcitx5 voice subtype in `res/xml/method.xml`.
- Do not implement Fcitx5 key gestures here: this IME cannot receive touch events
  from another IME.
- Do not add remote Git configuration or push unless explicitly requested.

## Verification

Run after code changes:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

For macOS without a configured JDK:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

For recording, inference, IME switching, or permission changes, install the APK
and verify the complete flow on an ARM64 Android device.

## Roadmap

Completed: IME integration, AudioRecord, SenseVoice initialization, offline
transcription, commitText, and return to Fcitx5.

Next: OpenCC, terminology correction, Silero VAD, settings, and release hardening.
