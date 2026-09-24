# 文献与 RAG：首条可验收链路

## 当前能力

项目内导入 PDF、UTF-8 TXT 或 Markdown；原文件保存在 `WORKBENCH_DATA_DIR/literature/`，数据库保存标题、文件 SHA-256、大小、页数、片段数与导入时间。PDF 逐页提取文字，切块始终留在同一页。相同文件哈希在同一项目内拒绝重复导入；修改后的文件会成为另一条独立记录，旧记录与引用不变。

“检索证据”保留版本为 `lexical-bm25-v1` 的词法基线。配置多语言 embedding 模型并为文献建立索引后，使用 `hybrid-bm25-embedding-v1`，按倒数排名融合词法与语义检索；中文问题可以召回英文片段，英文问题也可以召回中文片段。每次最多为当前项目或选中文献补建 200 段索引；旧文献无需重新上传，可以多次点击直到完成。索引保存在 PostgreSQL，并与模型地址、名称、显式 `EMBEDDING_REVISION` 和原始片段 SHA-256 绑定。模型权重更新后应变更 revision 并重建索引。未配置或未完成索引时，页面明确显示状态。

结果始终包含原文片段、PDF 物理页码、原文件与片段哈希以及可打开的原文件地址。回答模型可为前五个片段生成反方向译文，页面将原文与译文并列展示并标注模型来源；译文生成失败时只显示原文。译文不参与原文件哈希或引用定位。可检索选定项目的全部文献，或进一步限定到其中一篇；两种范围都由后端校验。打开原文件时重新核对 SHA-256；文件缺失或改变会报错。

“基于证据回答”只在服务端已有 `ASSISTANT_PROVIDER` 配置且可用时调用该模型。它把当前问题和前五个命中片段传给模型，要求分别输出中文和英文回答，并在两种语言的事实陈述后使用 `[C1]` 等编号；无命中时不调用模型，无引用或引用超出范围时拒绝展示回答。回答接口先返回双语文字与原文引用，页面随即单独请求证据译文；译文最多处理前五条、每批最多三条，失败批次逐条重试，未完成时保留原文并显示 `PARTIAL` 或 `FAILED`。返回状态 `GENERATED_UNVERIFIED` 表示模型文字尚未经过逐句事实核查。默认模型禁用时，入库与词法检索仍可用。

当前助手侧栏与文献问答是两个独立入口，五节点 DEMO 工作流也没有自动读取文献。文献上传不会让 DEMO 节点变成真实研究执行。

## 接口

- `GET /api/literature/documents?projectId=UUID`：列出项目文献。
- `POST /api/literature/documents`：multipart 表单 `projectId`、`title`、`file`；返回文献记录。
- `GET /api/literature/documents/{id}/file`：校验哈希后返回原文件，PDF 可附 `#page=N` 定位。
- `GET /api/literature/search?projectId=UUID&q=...&limit=5&documentId=UUID`：`documentId` 可省略，省略时检索项目全部文献；提供时只检索该项目中的指定文献。`SearchResult {query,retrievalVersion,hits}`，`limit` 为 1 至 20。
- `POST /api/literature/index`：`{projectId,documentId?}`；显式补建最多 200 段向量索引，返回当前覆盖数与剩余数。
- `POST /api/literature/answer`：`{projectId,question,documentId?}`；范围规则与检索一致。先返回 `answerZh`、`answerEn`、原文引用和 `translationStatus=NOT_REQUESTED`；页面随后调用检索接口补齐译文。也可能返回模型未配置、调用失败或引用错误。

## 本机双语模型实验

本机可在 `compose.yaml` 与 `compose.local.yaml` 上追加 `compose.llama.local.yaml`。它用官方 llama.cpp CPU 服务镜像分别运行 BGE-M3 Q8 向量模型与 Qwen3 4B Q4 回答/翻译模型，放在本项目内部模型网络，不发布模型端口。先把已核对 SHA-256 的 GGUF 放入 `.local/models/`，再运行 `scripts/local-docker.ps1 -Action llama-up`；脚本会重新验算文件哈希。CPU 速度需实测，之后可评估 GPU 镜像。

也可追加 `compose.model-runner.local.yaml`，在 Docker Desktop Settings → AI 开启 Model Runner 与 GPU 推理，运行 `scripts/local-docker.ps1 -Action runner-up`。该路径通过容器内的 `http://model-runner.docker.internal/engines/v1` 调用模型，无须开放宿主 TCP。Model Runner 在 Windows 由 Docker Desktop 沙箱运行，不属于本项目 Compose 隔离；其当前可用性需单独验收。GPU 可用性、速度和真实研报质量必须分别实测。

备选方案是追加 `compose.models.local.yaml`，运行 `scripts/local-docker.ps1 -Action models-up` 启动独立 Ollama 容器、下载 `bge-m3` 和 `qwen3:4b`，权重放在独立 Docker 卷。也可分别将 `EMBEDDING_BASE_URL`/`EMBEDDING_MODEL` 和 `MODEL_API_BASE_URL`/`MODEL_API_MODEL` 指向经批准的 HTTPS 兼容接口；API 密钥只能放在服务端配置，不能写入仓库。

原文件最大 20 MB，PDF 最多 300 页，提取文字最多一百万字符，单文件最多 5000 个片段。TXT/Markdown 的 `pageNumber=1` 是内部定位值，界面标作“文本文件”，不代表存在物理页。无可提取文字时返回 `NO_EXTRACTABLE_TEXT`，不会把扫描件误标为已检索。

## 验收边界

目前尚无 OCR、表格与公式结构化、章节识别、专门重排模型、跨文献结论核查、检索评测集或问答持久化。PDF 中的页码是文件物理页码，可能与印刷页码不同。混合检索仅对已建立向量索引的片段具备跨语言召回，余下片段仍是词法检索。模型生成的译文和双语回答都可能出错，特别是数值、否定和专业术语，需要对照原文。模型被配置为远端服务时，建立索引会发送原文片段，检索会发送问题，译文及回答会发送命中原文片段。

下一轮可使用固定问题集验证页码引用、Recall@5、MRR、无证据拒答和模型引用正确性，然后考虑 OCR、混合检索与回答记录。每项提升都应与当前词法基线对比，不以回答流畅度代替引用质量。

已核对用户提供的本地 `Literature-RAG-Assistant` 副本。代码与迁移结论见 [旧项目基线核对](LITERATURE-BASELINE-REVIEW.md)。当前 Java/Vue 实现没有复制旧仓库代码。

本机阶段性实测记录见 [双向文献检索实验](LITERATURE-BILINGUAL-LOCAL-ACCEPTANCE.md)。
