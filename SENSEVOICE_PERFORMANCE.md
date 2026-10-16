# SenseVoice 端侧性能审视（2026-10-16）

## 当前实现与本次调整

- sherpa-onnx 1.13.4，ARM64，原版 SenseVoice INT8 ONNX，CPU、2 个推理线程；语言自动识别，保留 ITN。
- 16 kHz 单声道 PCM，按住期间仅录音，松手后离线推理一次，不做分块重复识别或静音端点检测。
- SpeechEngine 单线程串行管理加载、识别、切换和释放，同一模型复用 native session，每次只创建并释放音频 stream。
- 快速启动改为进程内保留一个模型；取消前台服务、通知权限、常驻通知和 START_STICKY。进程回收后按需重载，允许录音先于模型就绪。
- 录音电平计算改为直接遍历 ShortArray，移除每 512 个采样点一次的 take()/装箱列表分配。
- 未增加唤醒锁、后台麦克风、定时保活或自动拉起。模型缓存不等于永久保活，也不能据此宣称耗电降低了某个比例。

## 结论与未验证项

当前方案是合理的兼容性基线，不能认定为 Snapdragon 8 Gen 2 上最快、最省电的配置。最快与每次识别耗能最低可能对应不同线程数；在其他 ARM CPU 上的 RTF 不能直接外推本机。

保留 CPU 两线程，暂不盲目增加线程或固定 CPU 核心。应使用相同的 3/10/30/60 秒中英混说样本，对比 1/2/4 线程的冷启动、热推理 P50/P95、热状态、内存和每次识别能耗；记录识别错误率，避免用精度退化换速度。测量应在真机重复运行，电量百分比不足以说明单次耗能。

ONNX Runtime 建议量化模型从 CPU provider 开始。NNAPI 并非自动更快，支持不全导致图分割与 CPU 回退时可能变慢。QNN/高通 NPU 值得单独验证，但需要匹配的原生库、SDK 和转换模型，不是将 provider 字符串改为 qnn；现成 5/10 秒等固定长度模型还涉及长句分段、边界精度和额外延迟。NCNN 也是候选后端，需要独立基准，暂不替换现有稳定路径。

当前没有连接用户的 Android 真机，没有获取真实延迟、能耗或进程驻留时间。CI 只验证编译、单元测试、打包及签名。

## 原始资料

- [SenseVoice 模型与线程基准](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)
- [ONNX Runtime 移动端部署](https://onnxruntime.ai/docs/tutorials/mobile/)
- [NNAPI provider 的限制](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html)
- [QNN Android 适配与依赖](https://k2-fsa.github.io/sherpa/onnx/qnn/build-android-demo.html)
- [NCNN SenseVoice](https://k2-fsa.github.io/sherpa/ncnn/sense-voice/pretrained.html)
