# LocalVoiceIME

完全离线的 Android 语音输入法，配合
[Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 使用。

点击 Fcitx5 的麦克风后，系统切换到 LocalVoiceIME。录音结束后，应用在手机本地
使用 sherpa-onnx + SenseVoice Small INT8 识别语音，提交文字并自动切回 Fcitx5。

> 音频只在内存中处理，不保存、不上传；应用未申请网络权限。

## 功能

- 16 kHz、单声道、PCM 16-bit 本地录音
- SenseVoice Small INT8 中英文混合识别
- 后台推理，不阻塞输入法界面
- 识别结果通过 `commitText()` 输入当前文本框
- 识别完成后自动返回 Fcitx5，也可手动返回
- 单次录音最长 60 秒
- 输入目标变化保护
- Android 8.0+，当前仅打包 `arm64-v8a`

```mermaid
flowchart LR
    A["Fcitx5 麦克风"] --> B["AudioRecord"]
    B --> C["SenseVoice 本地推理"]
    C --> D["commitText"]
    D --> E["返回 Fcitx5"]
```

## 准备模型

模型约 228 MB，不提交到 Git。下载官方模型包：

```bash
curl -L --fail -o /tmp/sensevoice-int8.tar.bz2 \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"

shasum -a 256 /tmp/sensevoice-int8.tar.bz2
```

预期 SHA-256：

```text
7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e
```

解压模型和词表：

```bash
mkdir -p app/src/main/assets/sense-voice
tar -xjf /tmp/sensevoice-int8.tar.bz2 \
  -C app/src/main/assets/sense-voice \
  --strip-components=1 \
  sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/model.int8.onnx \
  sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/tokens.txt
```

## 构建与安装

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS 如果找不到 Java，可使用 Android Studio 内置 JBR：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

安装后，在 Android 的“管理屏幕键盘”中启用“本地语音输入”。它是输入法服务，
没有普通桌面入口。

## 使用

1. 将 Fcitx5 设为当前输入法。
2. 点击 Fcitx5 工具栏的麦克风。
3. 等待 SenseVoice 显示“已就绪”。
4. 点击“开始录音”并说话。
5. 点击“停止录音”。
6. 识别文字自动输入，随后返回 Fcitx5。

首次使用会请求麦克风权限。

## 隐私与限制

- 仅申请 `RECORD_AUDIO`，不申请 `INTERNET`。
- PCM 只暂存在进程内存，输入法隐藏时停止录音并释放资源。
- SenseVoice 是非流式模型，停止录音后才开始识别。
- APK 包含模型和原生库，Debug APK 约 264 MB。
- 当前只支持 ARM64 真机，不支持 x86 模拟器。
- Fcitx5 的空格触摸事件无法由独立输入法接收，因此不支持按住空格录音。

## 路线图

- [x] Voice IME 与 Fcitx5 集成
- [x] AudioRecord
- [x] sherpa-onnx + SenseVoice Small INT8
- [x] 后台识别、提交文字、自动返回 Fcitx5
- [ ] OpenCC 简体转换
- [ ] 中英文专业术语修正
- [ ] Silero VAD
- [ ] 设置界面与 Release APK

## 第三方项目与许可证

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- [SenseVoice](https://github.com/FunAudioLLM/SenseVoice)
- [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android)
- [RIME](https://rime.im/)

仓库发布前请添加项目自己的 `LICENSE`，并确认模型和所有第三方组件的许可证及
再分发要求。
