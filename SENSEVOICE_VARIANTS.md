# SenseVoice 变种调研（2026-10-16）

场景：骁龙 8 Gen 2、Android 端侧输入法、普通话夹英语、非流式。以下为公开模型卡与维护者文档调研，未进行同机实测。

## 结论

目前找到的能力增强主要集中在方言或特定任务，没有足够证据表明某个微调变种能普遍提升普通话中英混说。默认继续保留原版 SenseVoice Small INT8。若重点是减小权重，值得另测 GGUF Q5/Q4；若重点是骁龙上的推理速度，值得评估 QNN/HTP 后端。两者都不是换一个下载链接就能完成的改动。

| 变种 | 强化方向与证据 | 对本工程的意义 |
| --- | --- | --- |
| ASLP-lab/WSYue-ASR，sensevoice_small_yue | 234M 参数，粤语微调。同一发布方评测中，内部对话 CER 21.08→19.19，Daily Use 5.74→5.24；HK 集 7.34→8.68，有回退 | 方言增强而非通用升级。sherpa-onnx 已提供 2025-09-09 INT8 导出，接入成本低；普通话中英混说仍需验证 |
| ChenChenyu/SenseVoiceSmall-finetuned | 粤语+四川话微调；作者自有测试去符号 CER 19.42%→12.94% | 自有测试结果不能当跨模型通用排名；需导出兼容 ONNX、配套词表与量化验证 |
| justinchuby/SenseVoiceSmall-Hakka | 从 ModelScope 原作者版本转换，客家话微调，F32 Safetensors；未给出标准对照指标 | 客家话场景可考虑；当前包不能直接替换 sherpa 模型 |
| robinsonphan/sensevoice-small-zh-pinyin-dual-ctc | 原共享编码器上新增拼音 CTC 头，面向发音分析和声调检查；AISHELL-1 训练 | 输入/输出契约已改变。不是面向中英输入法的直接替换；模型卡明确提示混说泛化限制 |
| Yvthyvq/Liujgoj-Cantonese-SenseVoiceSmall | 约 18 小时粤语对话，输出 Liujgoj 粤语拼音正词法 | 输出目标不同于日常汉字输入，不建议作为当前默认 |
| ldov/SenseVoiceSmall-gguf | 格式转换/量化；Q5_K_M 172 MB，Q4_K_M 146 MB。作者报告英语 LibriSpeech test-clean：F32 3.13% WER，Q5 3.18%，Q4 3.45% | 有减小权重的价值，不是识别能力增强。英语成绩不能推导中文/混说无损；需引入 transcribe.cpp/兼容原生后端及 JNI，不能由当前 OfflineRecognizer 直接加载 |
| OpenVoiceOS/sensevoice-small-onnx | 原版转 ONNX，INT8 约 241 MB；将 LFR/CMVN 折叠到图内，服务 onnx-asr | 无新增训练，能力不是升级；图输入和前处理约定不同，不能因扩展名相同就直接替换 |
| ThomasTheMaker/SenseVoiceSmall-RKNN2 | 面向 Rockchip RK3588 NPU 的部署版 | 硬件适配，不适用于骁龙 NPU |

## 骁龙加速路线

sherpa-onnx 文档将 SM8550 列在 QNN/HTP 支持设备范围中。此路线可继续使用 SenseVoice，但需要 QNN 原生库、正确导出包、时长/形状约束及目标手机兼容性验证；不能仅将 CPU provider 改为 QNN 就宣称完成，也不能保证量化前后准确率完全一致。

## 来源

- https://huggingface.co/ASLP-lab/WSYue-ASR
- https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html
- https://huggingface.co/ChenChenyu/SenseVoiceSmall-finetuned/blob/main/README.md
- https://huggingface.co/justinchuby/SenseVoiceSmall-Hakka
- https://huggingface.co/robinsonphan/sensevoice-small-zh-pinyin-dual-ctc
- https://huggingface.co/Yvthyvq/Liujgoj-Cantonese-SenseVoiceSmall
- https://huggingface.co/ldov/SenseVoiceSmall-gguf
- https://huggingface.co/OpenVoiceOS/sensevoice-small-onnx
- https://huggingface.co/ThomasTheMaker/SenseVoiceSmall-RKNN2
- https://k2-fsa.github.io/sherpa/onnx/qnn/index.html
