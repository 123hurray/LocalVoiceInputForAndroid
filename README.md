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

默认开启。语音包下载后会提前加载，共享识别器在输入法关闭时不主动卸载。只在应用进程内缓存一个模型，不启动前台保活服务、不显示常驻通知、不申请通知权限，也不定时唤醒或重启进程。切换模型会释放旧模型。

Android 或厂商系统仍可能回收进程，回收后首次使用会重新准备；模型加载期间仍可先录音。关闭快速启动时释放缓存，之后输入法服务销毁时也释放模型。系统录音指示由 Android 管理，录音期间正常显示。

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

不需要预先下载模型即可构建。推送 main 或手动运行 **Build Android APK**，成功后在该次 Actions 页下载 `LocalVoiceInput-arm64` artifact，解压得到可安装的 Debug APK。所有发布构建必须使用仓库 Actions Secrets 中的固定签名。构建前核对 `.github/signing-certificate.sha256`，打包后再核对 APK 签名；密钥缺失或指纹不一致直接失败，不自动生成新密钥。

已按仓库所有者授权配置固定签名，使用：`ANDROID_SIGNING_KEYSTORE_BASE64`、`ANDROID_SIGNING_STORE_PASSWORD`、`ANDROID_SIGNING_KEY_ALIAS`、`ANDROID_SIGNING_KEY_PASSWORD`。此前默认 Debug 密钥的实际路径为 `/home/runner/.config/.android/debug.keystore`，旧缓存配置未保存该文件；旧 runner 的私钥无法从 APK 公钥证书恢复。如果改用新固定密钥，已安装的不同签名版本需要重装一次，之后才可持续覆盖升级。

模型切换、常驻内存和麦克风行为需要在 ARM64 真机上验证；CI 单元测试与构建不能替代真机效果和耗时测试。

## 项目与模型

基于 Mesong21/LocalVoiceInputForAndroid；推理使用 sherpa-onnx。模型目录和校验信息见 `app/src/main/assets/model-catalog.json`，均来自 sherpa-onnx 维护者提供的 Hugging Face 导出。

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- [SenseVoice](https://github.com/FunAudioLLM/SenseVoice)
- [Paraformer](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html)
- [Whisper](https://github.com/openai/whisper)
- [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android)

代码与模型的许可证及再分发要求需分别遵守。

## 实时中英输入（1.2.0）

在设置中下载并选择「Paraformer · 实时中英版（含标点）」，约 298 MiB，包含 encoder/decoder INT8、词表及 CT-Transformer INT8 标点模型。按住讲话时在语音面板预览文字，松手后刷新流式尾帧并自动补标点，然后一次提交到目标文本框。停顿不会自动提交，单次上限 60 秒，上滑取消。首次加载时可以先录音，模型准备后追上进度。

这一路径不使用 SenseVoice、不进行离线 ASR 后校准，也不承诺无限回改已经稳定的前文。预览暂不实时添加标点，标点在松手后统一恢复，减少反复推理和文字抖动。读取音频与推理分别运行；有上限的内存队列采用合并调度，不为每次读取堆积 worker 任务。取消后清空待处理数据，过期结果不提交。共享引擎切换时释放旧模型；快速启动仍无常驻通知。

模型来源：流式中英 Paraformer 使用 sherpa-onnx 维护者的 Hugging Face 导出，标点使用 ranger810 镜像的 CT-Transformer INT8 ONNX；模型清单固定源提交、字节数和 SHA-256，下载完成校验后才可使用。对应模型及转换代码许可应分别遵守。

实机验收应覆盖：首次加载立即讲话、中英混说、长停顿、60 秒上限、上滑取消、切换输入框、删除模型、切回其他语音包及低高度面板。CI 不能替代 ARM64 麦克风与 native 推理性能实测。

## 纠错面板（1.3.0）

语音面板右上角、设置图标左侧提供「纠错」开关，默认关闭，状态会保存。开启后，松手识别完成先显示校对面板，点击「输入」才提交。点击选择词块，横向滑动连续选择多个词，上下滑动滚动长文本；「按词/按字」切换到更细粒度。可删除所选内容、撤销、重录或取消。

选择内容后点击「候选」，按需使用另一款已下载的离线语音包识别本次完整录音，再用文字编辑距离对齐提取替换建议。优先 SenseVoice、离线 Paraformer，其次 Whisper（只有不超过其时长限制的录音才参与）。只缓存本次的第二模型结果，反复选词不重复识别。候选是另一模型的建议，未经用户选择不会修改文字，不提供虚构概率或声称是原模型 N-best。当前不是基于字词时间戳的局部音频重识别，文本差异较大时对齐也可能不准确。

候选功能需要至少另一款已下载且适用的语音包。后台模型切换复用同一串行 worker，释放原模型后加载另一模型，以降低同时驻留的内存；代价是下一次录音可能需要重新加载主模型。默认关闭纠错时仍直接提交，流式主识别不自动调用 SenseVoice。

纠错录音只暂存于内存，确认输入、取消、重录或离开输入面板时清除，不保存、不上传。校对期间可以继续按原有方式返回键盘或进入设置，但未提交内容会丢弃。
