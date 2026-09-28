# 文献与 RAG：首条可验收链路

## 当前能力

项目内导入 PDF、UTF-8 TXT 或 Markdown；原文件保存在 `WORKBENCH_DATA_DIR/literature/`，数据库保存标题、文件 SHA-256、大小、页数、片段数与导入时间。PDF 逐页提取文字，切块始终留在同一页。相同文件哈希在同一项目内拒绝重复导入；修改后的文件会成为另一条独立记录，旧记录与引用不变。

“检索证据”的词法版本为 `lexical-bm25-v2`；`lexical-bm25-v1` 是历史基线。配置多语言 embedding 模型并为文献建立索引后，使用 `hybrid-bm25-embedding-v3`：以最接近问题的向量片段为基准筛选候选，词法匹配只在候选内小幅加分；尚未建索引的片段仍可通过词法检索。英文词法检索会忽略常见功能词。PDF 目录点线和数字密集表格会轻微降权，可减少这类噪声与长研报免责声明压过正文证据。本地 BGE-M3 的最小余弦相似度设为 0.53；其他向量模型可通过 `EMBEDDING_MIN_COSINE` 单独校准，默认值 0.30。阈值仍需用固定问题集验证，不能视为相关性保证。每次最多为当前项目或选中文献补建 200 段索引；新增文献也需补建。索引保存在 PostgreSQL，并与模型地址、名称、显式 `EMBEDDING_REVISION` 和原始片段 SHA-256 绑定。模型权重更新后应变更 revision 并重建索引。未配置或未完成索引时，页面明确显示覆盖数与缺口。

结果始终包含原文片段、PDF 物理页码、原文件与片段哈希以及可打开的原文件地址。页面默认只检索和显示原文；用户可选中不超过 600 字的句段，按需请求反方向模型译文，并同时查看所选原文。译文不参与原文件哈希或引用定位。可检索选定项目的全部文献，或进一步限定到其中一篇；两种范围都由后端校验。打开原文件时重新核对 SHA-256；文件缺失或改变会报错。

“实验性双语回答”只在服务端已有 `ASSISTANT_PROVIDER` 配置且可用时调用该模型。它把当前问题和前三个命中片段传给模型，对正文中的清单句额外重复短原文窗口，要求分别输出中文和英文回答，并在两种语言的事实陈述后使用 `[C1]` 等编号；无命中时不调用模型。完整向量索引的混合检索首条分数低于 `workbench.literature.answer.min-score`（当前实验默认 0.53）时，只显示候选原文并返回 `NO_EVIDENCE`；模型也可显式判断候选片段不足，生成回答则必须有有效引用。这个分数只基于本机小样本校准，不能证明内容相关；0.53 向量阈值也可能漏掉换一种问法的有效证据。回答接口返回双语文字和原文引用，页面不再自动为引用生成译文。按需译文的 JSON 输出无效时尝试一次纯文本翻译，明显过短的译文会被拒收；失败时保留原文。返回状态 `GENERATED_UNVERIFIED` 表示模型文字尚未经过逐句事实核查。默认模型禁用时，入库、词法检索和证据卡记录仍可用。

复现证据卡保存**从原文片段中选取的精确引文**、所属项目和文献、PDF 物理页、原文件 SHA-256、片段 SHA-256，以及研究主张、数据与口径、可用时间、复现步骤、实际结果和结果差异。后端校验引文确实来自该项目的片段；保存后引文和来源标识不能改写。研究记录可编辑，编辑后自动退回“待核对”；“已人工核对”须手动标记。证据卡仅是可追溯的研究记录，不证明引文支持研究主张，也不执行计算或确认数据可用时间。

当前助手侧栏与文献问答是两个独立入口，五节点 DEMO 工作流也没有自动读取文献。文献上传不会让 DEMO 节点变成真实研究执行。

## 接口

- `GET /api/literature/documents?projectId=UUID`：列出项目文献。
- `POST /api/literature/documents`：multipart 表单 `projectId`、`title`、`file`；返回文献记录。
- `GET /api/literature/documents/{id}/file`：校验哈希后返回原文件，PDF 可附 `#page=N` 定位。
- `GET /api/literature/search?projectId=UUID&q=...&limit=5&documentId=UUID&translate=false`：`documentId` 可省略，省略时检索项目全部文献；提供时只检索该项目中的指定文献。`translate=false` 可先取原文命中，省略时生成译文。`SearchResult {query,retrievalVersion,hits,semanticStatus,translationStatus,indexedChunks,totalChunks}`，`limit` 为 1 至 20。
- `POST /api/literature/index`：`{projectId,documentId?}`；显式补建最多 200 段向量索引，返回当前覆盖数与剩余数。
- `POST /api/literature/answer`：`{projectId,question,documentId?}`；范围规则与检索一致。返回 `answerZh`、`answerEn`、原文引用和 `translationStatus=NOT_REQUESTED`；页面仅在用户主动选择句段时另行请求译文。也可能返回模型未配置、调用失败或引用错误。
- `POST /api/literature/translate`：`{projectId,chunkId,sourceText}`；只翻译选中且属于该项目片段的最多 600 字原文；失败时保留原文并返回状态。
- `GET /api/evidence-cards?projectId=UUID`、`POST /api/evidence-cards`、`PUT /api/evidence-cards/{id}`、`POST /api/evidence-cards/{id}/review?projectId=UUID`、`POST /api/evidence-cards/{id}/reopen?projectId=UUID`：项目内证据卡的创建、查看、研究字段编辑和人工状态记录。

## 本机双语模型实验

本机可在 `compose.yaml` 与 `compose.local.yaml` 上追加 `compose.llama.local.yaml`。它用官方 llama.cpp CPU 服务镜像分别运行 BGE-M3 Q8 向量模型与 Qwen3 4B Q4 回答/翻译模型，放在本项目内部模型网络，不发布模型端口。BGE-M3 的物理批大小设为 2048，以处理本项目现有的中文 PDF 长片段。先把已核对 SHA-256 的 GGUF 放入 `.local/models/`，再运行 `scripts/local-docker.ps1 -Action llama-up`；脚本会重新验算文件哈希。CPU 速度需实测，之后可评估 GPU 镜像。

也可追加 `compose.model-runner.local.yaml`，在 Docker Desktop Settings → AI 开启 Model Runner 与 GPU 推理，运行 `scripts/local-docker.ps1 -Action runner-up`。该路径通过容器内的 `http://model-runner.docker.internal/engines/v1` 调用模型，无须开放宿主 TCP。Model Runner 在 Windows 由 Docker Desktop 沙箱运行，不属于本项目 Compose 隔离；其当前可用性需单独验收。GPU 可用性、速度和真实研报质量必须分别实测。

备选方案是追加 `compose.models.local.yaml`，运行 `scripts/local-docker.ps1 -Action models-up` 启动独立 Ollama 容器、下载 `bge-m3` 和 `qwen3:4b`，权重放在独立 Docker 卷。也可分别将 `EMBEDDING_BASE_URL`/`EMBEDDING_MODEL` 和 `MODEL_API_BASE_URL`/`MODEL_API_MODEL` 指向经批准的 HTTPS 兼容接口；API 密钥只能放在服务端配置，不能写入仓库。

原文件最大 20 MB，PDF 最多 300 页，提取文字最多一百万字符，单文件最多 5000 个片段。TXT/Markdown 的 `pageNumber=1` 是内部定位值，界面标作“文本文件”，不代表存在物理页。无可提取文字时返回 `NO_EXTRACTABLE_TEXT`，不会把扫描件误标为已检索。

## 验收边界

目前尚无 OCR、表格与公式结构化、章节识别、专门重排模型、跨文献结论核查、大规模人工标注检索评测集或问答持久化。PDF 中的页码是文件物理页码，可能与印刷页码不同。混合检索仅对已建立向量索引的片段具备跨语言召回，余下片段仍是词法检索。模型生成的译文和双语回答都可能出错，特别是数值、否定、机构名称和专业术语，需要对照原文。模型被配置为远端服务时，建立索引会发送原文片段，检索会发送问题，按需译文只发送选中原文，实验性回答会发送命中原文片段。证据卡记录人工输入，但当前没有用户身份验证；部署前不能把“已人工核对”归属到具体人员。

下一轮可使用固定问题集验证页码引用、Recall@5、MRR、无证据拒答和模型引用正确性，然后考虑 OCR、混合检索与回答记录。每项提升都应与当前词法基线对比，不以回答流畅度代替引用质量。

已核对用户提供的本地 `Literature-RAG-Assistant` 副本。代码与迁移结论见 [旧项目基线核对](LITERATURE-BASELINE-REVIEW.md)。当前 Java/Vue 实现没有复制旧仓库代码。

本机阶段性实测记录见 [双向文献检索实验](LITERATURE-BILINGUAL-LOCAL-ACCEPTANCE.md)、[2026-09-28 首轮固定题集](LITERATURE-EVAL-2026-09-28.md)及[自动评测迭代和留出题失败](LITERATURE-EVAL-ITERATION-2026-09-28.md)。
