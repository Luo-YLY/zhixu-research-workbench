# 文献与 RAG：首条可验收链路

## 当前能力

项目内导入 PDF、UTF-8 TXT 或 Markdown；原文件保存在 `WORKBENCH_DATA_DIR/literature/`，数据库保存标题、文件 SHA-256、大小、页数、片段数与导入时间。PDF 逐页提取文字，切块始终留在同一页。相同文件哈希在同一项目内拒绝重复导入；修改后的文件会成为另一条独立记录，旧记录与引用不变。

“检索证据”使用版本为 `lexical-bm25-v1` 的词法检索，不需要模型。结果包含原文片段、PDF 页码、原文件与片段哈希以及可打开的原文件地址。可检索选定项目的全部文献，或进一步限定到其中一篇；两种范围都由后端校验。打开原文件时重新核对 SHA-256；文件缺失或改变会报错。

“基于证据回答”只在服务端已有 `ASSISTANT_PROVIDER` 配置且可用时调用该模型。它把当前问题和前五个命中片段传给模型，要求引用 `[C1]` 等编号；无命中时不调用模型，无引用或引用超出范围时拒绝展示回答。返回状态 `GENERATED_UNVERIFIED` 表示模型文字尚未经过逐句事实核查。页面同时显示原文片段供人工复核。默认模型禁用时，入库与检索仍可用。

当前助手侧栏与文献问答是两个独立入口，五节点 DEMO 工作流也没有自动读取文献。文献上传不会让 DEMO 节点变成真实研究执行。

## 接口

- `GET /api/literature/documents?projectId=UUID`：列出项目文献。
- `POST /api/literature/documents`：multipart 表单 `projectId`、`title`、`file`；返回文献记录。
- `GET /api/literature/documents/{id}/file`：校验哈希后返回原文件，PDF 可附 `#page=N` 定位。
- `GET /api/literature/search?projectId=UUID&q=...&limit=5&documentId=UUID`：`documentId` 可省略，省略时检索项目全部文献；提供时只检索该项目中的指定文献。`SearchResult {query,retrievalVersion,hits}`，`limit` 为 1 至 20。
- `POST /api/literature/answer`：`{projectId,question,documentId?}`；范围规则与检索一致。返回 `{status,answer,retrievalVersion,citations}`，也可能返回模型未配置、调用失败或引用错误。

原文件最大 20 MB，PDF 最多 300 页，提取文字最多一百万字符，单文件最多 5000 个片段。TXT/Markdown 的 `pageNumber=1` 是内部定位值，界面标作“文本文件”，不代表存在物理页。无可提取文字时返回 `NO_EXTRACTABLE_TEXT`，不会把扫描件误标为已检索。

## 验收边界

目前尚无 OCR、表格与公式结构化、章节识别、向量检索、重排、跨文献结论核查、检索评测集或问答持久化。PDF 中的页码是文件物理页码，可能与印刷页码不同。词法检索对同义词和中英跨语言提问有明显限制。模型被配置为远端服务时，问题与命中原文片段会发送至该服务。

下一轮可使用固定问题集验证页码引用、Recall@5、MRR、无证据拒答和模型引用正确性，然后考虑 OCR、混合检索与回答记录。每项提升都应与当前词法基线对比，不以回答流畅度代替引用质量。

已核对用户提供的本地 `Literature-RAG-Assistant` 副本。代码与迁移结论见 [旧项目基线核对](LITERATURE-BASELINE-REVIEW.md)。当前 Java/Vue 实现没有复制旧仓库代码。
