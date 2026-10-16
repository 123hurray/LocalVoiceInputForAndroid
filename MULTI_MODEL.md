# 模型实现说明

支持 SenseVoice Small INT8、离线 Paraformer INT8，以及流式 Paraformer + CT-Transformer INT8 标点恢复。

离线模型使用 OfflineRecognizer，流式模型使用 OnlineRecognizer。流式路径不进行 SenseVoice 自动后校准；开启纠错后，仅在选词时按需运行其余已下载模型，生成最多两款其他模型的替换建议并去重。

SpeechEngine 串行管理 native 引擎，切换时释放旧引擎；模型仅保留在进程内，无前台保活服务与常驻通知。录音为 16 kHz 单声道 PCM，最多 60 秒，加载时也能先录音。所有音频只留在内存，取消、提交或退出后解除引用。

模型通过固定版本、字节数和 SHA-256 校验后安装。设置支持下载、暂停、删除、选择。

真机检查：录音和实时文字、长停顿、滑选、候选替换、按住重录、输入提交、输入框切换、录音权限与低空间恢复。CI 编译和测试不能代替 ARM64 真机检查。
