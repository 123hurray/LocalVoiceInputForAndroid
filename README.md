# 本地语音 · Android

配合 Fcitx5 for Android 使用的本地语音输入键盘。按住说话，松手输入，上滑取消；中途停顿不会结束录音。

## 使用

1. 安装 GitHub Actions 构建的 `LocalVoiceInput-arm64.apk`（Android 8.0+，ARM64）。
2. 打开「本地语音」，下载一个语音包，推荐先使用 SenseVoice。
3. 点击「启用本地语音键盘」和「允许使用麦克风」。
4. 从 Fcitx5 的语音入口进入，或使用系统键盘切换器切到「本地语音」。
5. 按住按钮说话，松开后识别并输入。上滑再松手会取消。

主界面采用暗色风格。左上角返回键盘，右上角打开设置；文字提示区下方的整片面板都可按住说话，中央小按钮只作操作提示。

语音包已下载并授权麦克风后，即使首次加载尚未结束，也能立即按住录音。录音与准备并行；松手时若尚未就绪，音频只在内存中等待，准备完成后自动识别。退出页面会丢弃等待中的录音。

## 语音包

| 名称 | 下载量（约） | 定位 |
| --- | --- | --- |
| SenseVoice Small INT8 | 228 MiB | 默认，中英混合输入 |
| Paraformer INT8 | 213 MiB | 中英输入对照 |
| Whisper Tiny 多语言 INT8 | 99 MiB | 轻量实验选项 |
| Whisper Base 多语言 INT8 | 153 MiB | 多语言实验选项 |

下载只获取 INT8 权重和配套词表，不打包浮点权重。使用固定版本和逐文件 SHA-256 校验，下载完整并校验成功后才显示可使用。支持暂停与续传；被系统结束后再次点击下载可继续。下载时请保持设置页打开。

Whisper 是多语言模型，不能保证句内中英混说效果优于 SenseVoice。它目前单次最多录音 25 秒，其他模型最多 60 秒；达到上限会自动识别。当前未实现长音频分段拼接或标点恢复模型，Paraformer 的标点/数字格式可能与 SenseVoice 不同。

## 键盘高度与切换

设置页可调整 200–500 dp 的面板高度，默认 320 dp。将其调到与 Fcitx5 键盘一致，可减少宿主应用因高度不同而移动。语音输入法自身窗口关闭切换动画，横屏也不进入全屏编辑模式；系统或另一个输入法的切换动画仍由其自身控制。

## 快速启动

默认开启。语音包下载后会提前加载，共享识别器在输入法关闭时不主动卸载。用户可通过设置或常驻通知关闭快速启动。

前台服务只保留模型，不打开麦克风、不持有唤醒锁。Android 或厂商系统仍可能回收进程，回收后首次使用需重新准备。关闭快速启动后，不再启动保活服务，输入法服务销毁时释放模型。

## 隐私

- 音频只在内存中处理，不保存、不上传、不记录日志。
- INTERNET 仅用于用户主动下载语音包，识别过程完全离线。
- 下载模型存放在应用私有、不备份目录，无需文件访问权限。
- 仅在按住说话或主动无障碍点按录音期间使用麦克风；离开输入法立即停止。
- 使用会话与输入位置检查，过期结果不提交到新输入框。

## 构建

JDK 17，Android SDK 与版本见 `.github/workflows/android.yml`。

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

不需要预先下载模型即可构建。推送 main 或手动运行 **Build Android APK**，成功后在该次 Actions 页下载 `LocalVoiceInput-arm64` artifact，解压得到可安装的 Debug APK。所有成功发布的构建必须使用同一个 CI Debug 签名，以便覆盖安装。工作流从已交付 APK 固定公开证书 SHA-256，构建前校验缓存密钥、打包后再校验 APK 签名；缓存缺失或指纹不一致直接失败，不自动生成新密钥。原密钥目前保留在 GitHub Actions 缓存中，若缓存被清理必须恢复原密钥才能继续构建。

模型切换、常驻内存和麦克风行为需要在 ARM64 真机上验证；CI 单元测试与构建不能替代真机效果和耗时测试。

## 项目与模型

基于 Mesong21/LocalVoiceInputForAndroid；推理使用 sherpa-onnx。模型目录和校验信息见 `app/src/main/assets/model-catalog.json`，均来自 sherpa-onnx 维护者提供的 Hugging Face 导出。

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- [SenseVoice](https://github.com/FunAudioLLM/SenseVoice)
- [Paraformer](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html)
- [Whisper](https://github.com/openai/whisper)
- [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android)

代码与模型的许可证及再分发要求需分别遵守。
