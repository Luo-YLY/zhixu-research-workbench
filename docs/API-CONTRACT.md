# Research Workbench V0 API contract

Local-first, single-user modular monolith. Vue 3 + TypeScript frontend; Java 21 + Spring Boot backend; PostgreSQL primary storage, persistent H2 fallback. Five-node research workflow execution remains explicitly DEMO. The V0.2 contextual discussion interface and context preview are ready, with model providers disabled by default. Model API and Codex CLI adapters are reserved for later explicit server configuration; the assistant cannot execute or approve research tasks.

## HTTP conventions
- Base `/api`. JSON camelCase. Entity IDs are UUID strings; event IDs are ordered decimal strings from the database sequence. Timestamps ISO-8601 UTC.
- List endpoints return JSON arrays. Errors: `{ "code": "...", "message": "..." }` with 400/403/404/409/429 as appropriate.
- Loopback binding, same-origin requests; no arbitrary shell execution API.

## Endpoints and DTOs
- `GET /api/system`: `{appName, version:"0.4.0", database, executionMode: "DEMO", capabilities: [{key,label,status,description}]}`. Status `AVAILABLE`, `PLANNED`, or `CONFIGURATION_REQUIRED`. Default assistant capability is `CONFIGURATION_REQUIRED` with description "上下文对话界面已就绪，模型接口待配置". The planned Codex research executor is separate from the optional discussion adapter.
- `GET /api/dashboard`: `{projectCount,taskCount,activeRunCount,pendingApprovalCount,completedRunCount}`.
- `GET/POST /api/projects`: POST `{name,description}`; return `{id,name,description,createdAt}`.
- `GET /api/tasks?projectId=UUID` and `POST /api/tasks`: POST `{projectId,title,objective}`; return `{id,projectId,title,objective,createdAt}`. Description limits documented in backend validation.
- `POST /api/tasks/{taskId}/runs`: body `{}`; optional `Idempotency-Key` request header. Returns full RunDetail. Only one active run per task; conflict -> 409. Retries use a new run; old runs immutable.
- `GET /api/runs`: RunSummary[] `{id,taskId,taskTitle,projectId,status,executionMode,createdAt,updatedAt}`.
- `GET /api/runs/{id}`: RunDetail = RunSummary plus `{objective,nodes,events,artifacts,approval}`.
- Node `{id,key,label,position,status,startedAt,finishedAt,detail}`; statuses `PENDING/RUNNING/WAITING_APPROVAL/SUCCEEDED/FAILED/CANCELLED/REJECTED`.
- Run statuses `QUEUED/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED/REJECTED`.
- `POST /api/runs/{id}/cancel`: body `{}`; return RunDetail. Terminal runs -> 409.
- Event `{id,runId,type,message,createdAt}`. `GET /api/runs/{id}/events`: SSE named event `run` whose data is full RunDetail; id is last persisted event ID. Periodic heartbeat and reconnect supported. Ordinary GET RunDetail remains a fallback.
- `GET /api/approvals`: Approval[] `{id,runId,taskTitle,status,createdAt,decision,comment,decidedAt}`; statuses `PENDING/APPROVED/REJECTED/CANCELLED`.
- `POST /api/approvals/{id}/decisions`: `{decision:"APPROVE"|"REJECT",comment:string}`; return RunDetail. Cannot decide twice; reject requires comment. Approval is bound to the immutable run task snapshot.
- Artifact `{id,runId,name,mediaType,sha256,sizeBytes,createdAt,downloadUrl}`; `GET /api/artifacts/{id}/download` downloads persisted artifact bytes.

## Demonstration workflow
Five durable nodes: `TASK_SPEC` / 任务规范 -> `EVIDENCE` / 证据准备 -> `VALIDATE` / 结构校验 -> `APPROVAL` / 人工审批 -> `ARCHIVE` / 产物归档.
Worker asynchronously advances nodes 1–3 using deterministic demo handlers, pauses at human approval, then archives a manifest after APPROVE. REJECT is terminal. Demo evidence and all artifacts clearly marked `research_only`, `DEMO`, and no real report analysis/calculation performed. Initial project/task seed allowed, but no invented real research results.
Jobs survive restart: persisted node/run state; interrupted demo jobs can safely resume. State changes, approvals and event records transactional. Idempotency keys and task-row locks prevent duplicate active runs. This phase implements a fixed, versioned workflow template, not an arbitrary graph editor.

## Contextual assistant (V0.2)
The complete DTO and lifecycle contract is in [ASSISTANT-CONTRACT.md](ASSISTANT-CONTRACT.md).

- `GET /api/assistant/status`: `{provider:"UNCONFIGURED"|"CODEX_CLI"|"MODEL_API",available,version,message}`. Default `UNCONFIGURED` and `available:false`; no model request or adapter detection. Explicitly configured providers use local status checks only.
- `POST /api/assistant/context`: `{page,projectId?,taskId?,runId?,nodeKey?,selectedText?}` -> `{title,summary,content}`. Authoritative bounded context preview; no model request or file access.
- `GET/POST /api/assistant/sessions`: list sessions or create with `{title?}`.
- `GET /api/assistant/sessions/{id}`: session metadata plus persisted messages.
- `POST /api/assistant/sessions/{id}/messages`: `{text,includeContext,context?}`; optional session-scoped `Idempotency-Key`. While disabled, returns `409 ASSISTANT_UNAVAILABLE` without invoking adapters or creating a turn. After configuration, captures context, queues an assistant turn, and returns SessionDetail immediately. Poll the session endpoint for completion.
- `POST /api/assistant/sessions/{id}/cancel`: cancels the active turn; terminal or idle sessions return409.
- Input max6000, selected text max2000, context max12000, output max24000 characters; latest8 completed history messages are included within individual limits.
- Session status `IDLE/RUNNING`; message status `QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/INTERRUPTED`. One active assistant turn globally; same-session duplicate409, other-session occupied429. Same idempotency key/request does not invoke the model twice; changed body with reused key409.
- Unlike deterministic DEMO workflow recovery, assistant calls interrupted by restart are marked INTERRUPTED and are never automatically replayed. Saved contexts and transcripts are persisted in PostgreSQL or the selected H2 file database.

## Daily planning (V0.3)
- All dates and recurring rules use `Asia/Shanghai`; time values use `HH:mm`. Existing records are stored in PostgreSQL or H2 through Flyway V3.
- `GET /api/schedules`: list `{id,taskId,taskTitle,frequency,weekday,plannedTime,startDate,active,createdAt}`. `frequency` is `DAILY` or `WEEKLY`; weekly uses ISO weekday 1–7, daily uses null.
- `POST /api/schedules`: `{taskId,frequency,weekday?,plannedTime,startDate}`. `POST /api/schedules/{id}/pause|resume` changes generation of later occurrences. Already generated plan items remain available.
- `GET /api/plans?date=YYYY-MM-DD`: list that day's items and materialize due recurring items exactly once. A midnight scheduler also materializes the current date. Date defaults to today and is limited to one year in either direction.
- `POST /api/plans`: `{title,date,taskId?,plannedTime?}` creates a manual item. `POST /api/plans/{id}/complete|reopen` stores its completion state.
- Plan item `{id,date,plannedTime,taskId,scheduleId,title,status,createdAt,completedAt}` has status `TODO` or `DONE`. A plan item is an agenda entry, not a workflow run; recurring rules do not launch DEMO research runs.

## Literature and evidence retrieval

The implemented literature API, file limits, retrieval version, citation fields, and model availability boundary are specified in [LITERATURE-RAG.md](LITERATURE-RAG.md). Document retrieval is scoped by project. Original file download verifies SHA-256. Lexical search is available while the model provider is disabled; generated answers require an explicitly configured provider and remain unverified research assistance.

## Frontend scope
Chinese desktop workbench with left navigation: overview, projects/tasks, daily planning, literature, runs, approvals, roadmap. Core actions hit real API; empty/loading/error states, accessible labels, no fabricated metrics. Run detail has node graph, event timeline, task snapshot, and artifact links. OCR, semantic retrieval, evaluation corpus, and agent execution remain planned.
