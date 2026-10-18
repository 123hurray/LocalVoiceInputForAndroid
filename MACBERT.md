# MacBERT 错词检查

- 模型：shibing624/macbert4csc-base-chinese，Apache-2.0。
- 固定版本：615e6e09ef9a69ec487bc7c641ec3a311e2c11b9。
- 部署文件：官方 onnx/model.onnx（474336933 字节）与 vocab.txt（109540 字节）。SHA-256 固定在 MacBert.kt，下载先进入私有 staging 目录，通过逐文件长度和哈希校验才启用。允许显式暂停、续传、删除。
- 采用 FP32 CPU 推理。没有采用此前验证退化的动态 INT8；FP16 需要手机端另行验证运行后端。
- ONNX Runtime Java bridge 固定 1.27.0，与 sherpa-onnx 1.13.4 所带核心一致。构建时从 Java bridge AAR 中明确移除重复核心，只使用 sherpa 的核心。Actions 检查 APK 中唯一核心及两个 JNI bridge 的 VERS_1.27.0。
- 原文 UTF-16 跨度保持不变，只接受已知、单字符中文位置的单字符中文替换。英文、数字、空白、标点、emoji、未知字和子词从原文复制。不会将解码文本直接覆盖原文。
- 每 96 个 token 为一个核心窗口，左右各保留最多 24 token 上下文；每个位置仅写入一次，长草稿不会静默截断。
- 不自动检查。纠错面板「查错词」只生成建议；选中文字时只采用该范围的修改，未选中时采用整段的建议修改。点选建议后才修改草稿，并保留撤销。输入位置或草稿变化后丢弃过期结果。
- 检查、下载、删除、模型释放由独立单线程队列串行执行；CPU 线程数为 2。空闲 60 秒释放模型，内存紧张时也释放。无前台服务、通知、麦克风保活或文本上传。

## 验证

`validation/macbert-onnx-deployment.json`：实际部署的 ONNX 文件、上述窗口设置与保护规则跑完 40 条测试，输出与之前已验证的受保护 FP32 结果 40/40 一致。42 条 tokenizer 参考向量覆盖原测试集、中英混说、大小写、数字、重音、emoji 与补充汉字。单元测试另外检查修改范围与长文本窗口覆盖。

当前环境没有 ARM64 安卓设备。签名 APK 构建、单元测试和动态库打包检查由 GitHub Actions 执行；真机加载延迟、内存峰值及耗电仍需试用验证。

## 来源与许可

模型与测试词表来自 https://huggingface.co/shibing624/macbert4csc-base-chinese ，作者 Xu Ming（shibing624），基于 MacBERT 中文预训练模型。来源说明：https://github.com/shibing624/pycorrector 。Apache License 2.0 文本见 LICENSES/MacBERT-Apache-2.0.txt。模型权重不提交到 Git，也不随 APK 打包。
