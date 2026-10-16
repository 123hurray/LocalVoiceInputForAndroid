# 纠错候选调研与当前边界

## 已交付路径

现有 SenseVoice、离线 Paraformer、流式 Paraformer + 标点共三个语音包。用户选词后按需运行其余已下载模型，按文字对齐提取建议并去重；最多有两款其他模型的不同建议。不人为凑数，不声称拥有准确的候选置信度。缓存本次结果，选择其他词不重复跑模型。流式主路径不会自动用 SenseVoice 校准。

## 真正的单模型 N-best

1. WeNet 的 CTC prefix beam search / attention rescoring 路径能生成 N-best；C API 明确提供 `wenet_set_nbest`，可输出多个 sentence，另有 `wenet_set_timestamp`。更贴近整句多候选及音频定位，但需要适配独立 native runtime、JNI、配套模型和词表，不能仅换当前 sherpa-onnx ONNX 文件。中文主模型的 N-best 能力不等于中英混说质量已经满足需求，必须另测。
2. 基于 SenseVoice CTC 输出扩展前缀束搜索，也可形成多条候选，需要修改解码器并暴露多候选 API；不一定要训练新模型。社区热词 fork 已有 CTC beam 实现，但其公开单结果接口不应被当作现成 N-best。
3. sherpa-onnx transducer 的 modified beam search 保留多路径不等于当前 Kotlin getResult 已返回 N-best。上游存在 N-best 暴露提案（#3953）；即使后续可用，也需要核对所用版本、支持的模型类别与移动端 API，不适用于普通 Paraformer 配置直接开启。

本轮没有新增未经验证的模型或自行生成伪候选。后续若主目标是稳定获取 3–5 条声学候选，优先评估 WeNet 兼容的中文/中英模型或扩展 SenseVoice CTC 解码器，并同时比较内存、延迟、错误召回率和候选多样性。

## 原始来源

- [WeNet runtime](https://github.com/wenet-e2e/wenet/blob/main/docs/runtime.md)
- [WeNet C API](https://github.com/wenet-e2e/wenet/blob/main/runtime/core/api/wenet_api.h)
- [WeNet 解码说明](https://wenet-e2e.github.io/wenet/tutorial_aishell.html)
- [sherpa-onnx N-best 提案](https://github.com/k2-fsa/sherpa-onnx/pull/3953)
- [SenseVoice CTC 热词解码 fork](https://github.com/wsweishu/sherpa-onnx-sensevoice-hotword)
