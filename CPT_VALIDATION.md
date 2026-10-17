# CPT-base 口述整理验证

结论：公开基础模型未通过，当前不接入。这个结论不否定 CS2W 论文中经过专项微调的 CPT 模型；公开的预训练权重与论文实验权重不能混同。当前公开仓库中未找到可直接下载的 CS2W 任务微调权重。

## 模型与运行环境

- 模型：`OpenMOSS-Team/cpt-base`，原 `fnlp/cpt-base`。
- 固定权重提交：`3dbd5b31feca1f7914680e5558b1faa72665433a`。
- 实际加载参数：144,913,152；当前更新版词表与论文中的旧版 121M 规模不同。
- 权重：`model.safetensors`，579,897,316 字节。没有缺失、额外或形状不匹配的参数。
- 官方结构实现：`FudanNLP/CPT/finetune/modeling_cpt.py`；具体 SHA-256 见结果 JSON。
- Linux CPU，2 线程，PyTorch 2.5.1+cpu，Transformers 4.40.2，FP32，beam=4，max_new_tokens=96。
- 仅为构造文本功能抽测，无用户录音或输入框内容。不是安卓性能测试，也不是正式数据集基准评测。
- 上游实现的文档装饰器与新版 Transformers 不兼容；测试只将该文档装饰器改为恒等装饰器，没有改动推理网络、权重或生成逻辑。

## 结果

| 项目 | 实际表现 | 判定 |
|---|---|---|
| 官方示例式补词 | 正确补出“中国” | 基础模型已正确加载，可运行 |
| 去语气词、重复 | 保留“嗯那个”“我们我们”，并添加新的口语成分 | 不合格 |
| 废弃片段修复 | 改写成“值得大家关注也值得我们关注的”，未忠实保留句意 | 不合格 |
| 否定表达 | 保留主要否定，但将“呃”变为“不过” | 仍有不必要修改 |
| 中英混合、专名、数字 | 丢失 Android API，并追加与原文不符的人名和时间 | 不合格 |
| 指令式去冗余 | 将指令本身当作正文生成，继续保留重复 | 不合格 |

完整输入、原始生成解码后的输出与每例耗时保存在 [validation/cpt-base-results.json](validation/cpt-base-results.json)。展示时去除了解码产生的 ASCII 空格，该归一化不影响这里观察到的专名、数字和语义错误。

## 重复测试

准备 Python 3.12、PyTorch 2.5.1 CPU 与 Transformers 4.40.2；在一个工作目录中保存上述固定提交的 config.json、vocab.txt、tokenizer_config.json、special_tokens_map.json 和 model.safetensors 到 model/ 子目录，并保存官方 modeling_cpt.py。执行：

```bash
python validation/validate_cpt.py /path/to/work-directory
```

脚本使用本地文件加载，不依赖在线推理服务，输出 results.json。后续若取得 CS2W 专项微调权重，应独立重测，再验证 INT8 量化、Android 推理、长文本及中英混说。

## 来源

- https://github.com/FudanNLP/CPT
- https://huggingface.co/OpenMOSS-Team/cpt-base
- https://github.com/guozishan/CS2W
- https://aclanthology.org/2023.emnlp-main.241/
