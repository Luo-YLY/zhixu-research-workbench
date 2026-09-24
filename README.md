# 知序 · 研究工作台

一个 Java + Vue 研究工作系统，可在 Windows 本机或服务器容器中运行。右侧研究助手的对话界面、上下文整理和可切换模型适配层已经就绪，**默认不连接任何模型**。V0.3 增加周期任务和每日计划；文献页面现可导入原文、按页检索并核对出处。研究执行器仍为 **DEMO**，用于验证任务管理、持久化、流程状态、人工审批和产物追溯，不把示例流程当作研报复现结果。

## 开始

需要 Java 21、Maven、Node.js 24、npm。命令在本目录执行。

```powershell
# 构建前端、运行后端测试，打包为包含前端的 JAR
./scripts/build.ps1

# 自动使用已配置的本地 PostgreSQL；没有配置时使用磁盘 H2
./scripts/start.ps1

# 显式选择存储
./scripts/start.ps1 -Database postgres
./scripts/start.ps1 -Database h2

# 停止应用；加 -WithDatabase 可一并停止本项目的数据库
./scripts/stop.ps1
```

打开 `http://127.0.0.1:18081`。两个数据库配置各自保存数据，切换配置不会自动迁移已有记录。

Windows 本机曾以磁盘 H2 完成启动、浏览器操作和重启验收。服务器使用专用 PostgreSQL：V0.2 演示流程已于 2026-09-23 验收，V0.3 的数据库迁移、每日计划页面和写入回读已于 2026-09-24 验收。两端数据库独立，不会自动同步。

## 每日计划（V0.3）

从左侧“每日计划”进入，可以手动添加某天的事项并关联研究任务，也可以为一个任务设定每天或每周的时间规则。到期事项会生成一次；可标记完成、重新打开，或暂停／恢复周期规则。页面及后端统一按北京时间计算日期。计划不会自动启动当前 DEMO 研究运行。

## 文献与 RAG

从左侧“文献与RAG”进入，选择项目，导入 PDF、UTF-8 TXT 或 Markdown，按问题检索项目全部文献或指定单篇。结果保留 PDF 物理页码、原文件哈希和片段哈希，并可打开出处。检索无需模型；“基于证据回答”仅在服务端已配置模型时可用，生成内容仍需对照出处人工核查。扫描件、公式和表格暂不保证可提取；缺少文字的文件会被明确拒绝。接口与限制见 [文献与 RAG 说明](docs/LITERATURE-RAG.md)。

## 第一条完整流程

1. 创建项目，写明研究范围。
2. 创建任务，填写标题与研究目标。
3. 启动示例运行，查看五个节点与事件时间线。
4. 在“人工审批”处暂停，审阅本次任务快照。
5. 批准后下载归档清单；退回必须填写原因。运行结束后再次执行会建立新的运行记录。
6. 重启应用，检查任务、审批、事件和产物仍然存在。

流程模板 V0：任务规范 → 证据准备 → 结构校验 → 人工审批 → 产物归档。证据准备只使用明确标记的示例内容，结构校验只验证流程输入，尚无 PDF 解析、真实数据校验、统计计算或 AI 研究结论。

## 项目结构

```text
backend/      Spring Boot API、领域服务、数据库迁移、后台执行器和测试
frontend/     Vue 3 + TypeScript 工作台
scripts/      Windows 构建、启动、停止及数据库管理
docs/         接口约定、架构和验收记录
.local/       本机数据库配置、数据、日志；被版本控制忽略
```

`frontend/dist` 会在构建时复制到后端静态资源目录，随后打包到 JAR。开发时可独立运行前端开发服务器；API 代理指向本地后端。

## 研究助手

打开右上角“研究助手”，可编辑问题、查看会话和当前项目／任务／运行／节点的上下文。侧栏跟随当前页面整理上下文，可展开查看，也可关闭附带上下文或手动引用选中文字。默认发送按钮不可用；后端同样拒绝未配置时发送。它不会自动读取屏幕、文档文件或服务器。

未来通过服务端环境变量选择 `ASSISTANT_PROVIDER=codex` 或 `model-api`，默认值为 `disabled`。禁用时不检测 CLI、不发模型请求；浏览器不能自行启用适配器或修改服务地址和密钥。当前不需要你配置登录或密钥。

已预留两种适配：Codex CLI 的 App Server，以及兼容 OpenAI Chat Completions 格式的 HTTP API。后一种使用 `MODEL_API_BASE_URL`、`MODEL_API_MODEL`、`MODEL_API_KEY`；本地无鉴权服务可不设密钥。不同厂商的私有协议需增加对应适配，不能认为所有模型直接通用。示例变量见 `.env.example`（不会自动加载）。启用并发送后，消息、有限历史和所选上下文会发到所选模型服务，并可能消耗对应账户额度。

Codex 本机版本和登录状态已检测到，但本次首次模型调用失败，未确定是鉴权还是其他原因；按用户决定暂停实接，未再重试。模型 API 仅以本地测试服务验证协议，尚未配置或验收真实厂商模型。

会话和消息存入工作台数据库。当前同时处理一个请求，最多等待 180 秒，可取消；异常不会用模拟回复代替，服务重启后未完成的消息会标记中断，不自动重发。每次发送使用最近最多 8 条历史及有长度限制的上下文，并不等同于无限记忆。当前界面轮询回答状态，在完成后显示全文。

回答下方的“转为任务草稿”会打开现有任务表单，供你选择项目、修改内容后保存。助手仅提供讨论与草稿；尚未接入研究节点执行、真实数值计算或文献检索。

接入细节与上下文边界见 [助手设计](docs/ASSISTANT.md)；接口见 [助手接口约定](docs/ASSISTANT-CONTRACT.md)。

## PostgreSQL

数据库连接通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 配置，详见 `.env.example`。便携安装脚本仅管理本项目的 PostgreSQL，使用 `127.0.0.1:55432`，无需安装 Windows 服务。

```powershell
./scripts/database-setup.ps1
./scripts/database-start.ps1
./scripts/database-stop.ps1
```

网络无法下载时，可先取得官方包，再运行 `./scripts/database-setup.ps1 -ArchivePath '完整ZIP路径'`。脚本记录官方来源、包版本和本地计算的校验值。安装成功后才会生成 `.local/database.json`。

本地随机密码保存在 `.local/database.json`，启动脚本只传入子进程环境，不把密码写入命令行或文档。这个文件属于敏感本机配置，不应提交或分享。数据迁移由 Flyway 管理；H2 是便于快速开发的备用配置，PostgreSQL 上的验收以实际测试记录为准。

## 当前边界与下一阶段

当前为单用户工作台：Windows 本机直接绑定回环地址；服务器容器仅将入口发布到服务器回环地址，再由 SSH 转发访问。跨站修改请求会被拒绝，但尚未实现登录、用户角色和多人协作；开放网络访问前需补这些能力。没有外部命令输入接口。

后续待接入：选择并验收讨论模型、真实 Codex/Python 研究执行、OCR 与更强检索、多人和多 Agent。周期任务与每日计划已实现为独立的待办管理，尚不调度真实研究执行。

详见 [接口约定](docs/API-CONTRACT.md)、[架构与阶段计划](docs/ARCHITECTURE.md) 和 [验收记录](docs/ACCEPTANCE.md)。

服务器部署状态和访问方式见 [部署记录](deploy/README.md)。

## 从哪里读代码

先沿着创建任务这条链路阅读：`frontend/src/App.vue` → `task/TaskApi.java` → `task/TaskService.java` → `task/TaskStore.java` → `db/migration/V1__research_workbench.sql`。

再阅读运行链路：`run/RunService.java` → `workflow/DemoWorker.java` → `workflow/DemoWorkflow.java` → `workflow/ResearchStepExecutor.java`。Java 文件均位于 `backend/src/main/java/local/research/workbench/`；迁移文件在 `backend/src/main/resources/`。
