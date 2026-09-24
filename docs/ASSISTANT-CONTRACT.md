# Contextual Research Assistant V0.2

Product: a collapsible right-side research assistant panel, anchored to the current workbench. On narrow screens it becomes a drawer. Explicit context preview with include/exclude toggle; discussion persists in the database. The model provider is disabled by default. Model API and Codex CLI adapters are reserved for later explicit server configuration; default startup and all default UI actions make no model request and do not detect or launch Codex. After configuration, user submission is the only trigger for a model call. No ambient screen capture, browser history, file crawling, or automatic research approval.

## API
- `GET /api/assistant/status`: `{provider:"UNCONFIGURED"|"CODEX_CLI"|"MODEL_API",available:boolean,version:string,message:string}`. Default `{provider:"UNCONFIGURED",available:false,...}`. It performs no model call and returns no credentials. Default disabled status does not invoke either adapter. An explicitly configured CLI adapter may perform local version detection; model API status checks server configuration locally without contacting the remote endpoint.
- `POST /api/assistant/context` with ContextRequest directly -> ContextSnapshot.
- ContextRequest: `{page:string,projectId?:string,taskId?:string,runId?:string,nodeKey?:string,selectedText?:string}`. Nullable IDs allowed; selectedText max2000; page max40. Server loads authoritative records and checks relationships; excludes raw secrets/files. Canonical run context includes project/task/run status, selected node, bounded recent events/artifact metadata, and DEMO boundary.
- ContextSnapshot: `{title:string,summary:string,content:string}`. `summary` human-readable, `content` bounded serialized context for provider. Preview is local only.
- `GET /api/assistant/sessions`: SessionSummary[] `{id,title,status,createdAt,updatedAt}`.
- `POST /api/assistant/sessions`: `{title?:string}` -> SessionDetail.
- `GET /api/assistant/sessions/{id}` -> SessionDetail.
- `POST /api/assistant/sessions/{id}/messages`: `{text:string,includeContext:boolean,context?:ContextRequest}` -> SessionDetail immediately; background execution. Optional `Idempotency-Key` header follows the retry rules below.
- `POST /api/assistant/sessions/{id}/cancel`: `{}` -> SessionDetail.
- SessionDetail extends summary with `messages: Message[]`. Message `{id,role,content,status,contextTitle,contextSummary,createdAt}`; roles USER/ASSISTANT; statuses QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/INTERRUPTED. Session status IDLE/RUNNING. One active turn globally in V0.2 (429 if occupied); duplicate same-session send409. Input max6000; latest 8 completed history messages, each clipped to 1800 characters; context max12000; response max24000. Session title max120; nodeKey max40. A submitted USER message is COMPLETED while the paired ASSISTANT message progresses from QUEUED to RUNNING and a terminal status.
- Same-origin guard applies. Failure must be honest and useful; no simulated model reply fallback.

## Retry, cancellation and persistence
- `Idempotency-Key` is optional, 1–128 characters from `[A-Za-z0-9._:-]`, scoped to one session. Same key and same request body return the current persisted SessionDetail without inserting messages or invoking a model again, including after completion/cancellation. Same key with a different body returns `409 IDEMPOTENCY_CONFLICT`. A deliberate new attempt must use a new key (or omit it).
- Without a matching idempotency key, a second message in the active session returns `409 ASSISTANT_BUSY`; another occupied session returns `429 ASSISTANT_CAPACITY`. A database singleton row serializes admission and terminal state changes. Invalid keys return400; disabled or unavailable model configuration returns `409 ASSISTANT_UNAVAILABLE`, without inserting a new turn or making a request.
- Context is resolved from authoritative database records in a separate repeatable-read transaction, validated against project/task/run relationships, bounded and persisted when sending. The saved context and prompt never change when page data later changes. Excluding context omits page and selection content; bounded conversation history is still sent. Oversized context retains a valid JSON envelope marked `truncated`.
- Model invocation runs on one dedicated worker thread, outside database transactions. Cancellation marks the assistant message CANCELLED and releases the session; the gateway is signalled to terminate its process. A replacement turn can queue, but the single worker starts it only after the prior invocation exits. A late prior reply or error cannot overwrite cancellation or reset the replacement turn's state.
- Service startup changes QUEUED/RUNNING assistant messages to INTERRUPTED and affected sessions to IDLE. No model request is automatically replayed. Explicit resubmission is required.
- Preview/status/session GET operations never initiate a model request. Default status does not inspect adapters. Only explicitly selecting the CLI adapter enables local CLI detection, cached for30seconds. Host names are restricted to `127.0.0.1`, `localhost`, and IPv6 loopback; foreign-origin mutations are rejected. CLI requests without Origin remain supported.
- Automated assistant completion/failure/recovery audit entries use actor `ASSISTANT_WORKER`; explicit user actions use `LOCAL_USER`. Conversation content and send-time context remain in the local database; the adapter sends the bounded prompt to the configured model provider only upon submission.

## Backend integration boundary
`AssistantProviderRouter` is the business-facing provider. It defaults to disabled and selects either `ModelApiGateway` or `CodexGateway` only after explicit server configuration. All implementations use the same `AssistantGateway` interface:
```
public interface AssistantGateway {
  record Availability(String provider, boolean available, String version, String message) {}
  Availability status();
  String answer(String prompt, java.util.function.BooleanSupplier cancelled) throws Exception;
}
```
`ModelApiGateway` reserves a generic OpenAI-compatible HTTP chat/completions interface. Endpoint, model and API key are supplied through server configuration; there is no browser endpoint for editing credentials or choosing arbitrary URLs. Its status method checks configuration without sending a request. `CodexGateway` remains a separate optional CLI adapter; only it may start a Codex process. The default router calls neither adapter's status nor answer method.

`ASSISTANT_PROVIDER` defaults to `disabled`; explicit supported values are `model-api` and `codex`. The model API adapter reads `MODEL_API_BASE_URL`, `MODEL_API_MODEL`, and `MODEL_API_KEY` on the server, all empty by default. The base URL is the API root (for example, an explicitly configured `/v1` root); the adapter appends `/chat/completions`. This phase does not set any provider or credentials and does not verify a real model connection.

Service assembles a bounded prompt of conversation and structured context, treating page content as untrusted data. Adapters enforce text-only answers, finite timeout/output and cancellation; the CLI adapter additionally handles process teardown and never interpolates a shell command. Backend captures context snapshot when sending is enabled, stores it with the message; no silently mutated prior snapshot. Backend context builder/worker never reads credential files or starts model calls on GET. Errors use provider-neutral wording such as "模型调用失败".

## UX
Assistant tab toggle in topbar. View remains available beside the main workspace; adjustable expanded width or full-width focus mode acceptable. Show context title and preview, checkbox "附带当前页面上下文", optional selected text collected ONLY by explicit "引用选中文字" action, clear references, suggestions matched to current page. Show local provider status and ongoing/cancel state. Persist sessions, offer new conversation/history. Plain escaped assistant text initially; no raw HTML rendering.

Task drafting should use the existing new-task form as an explicit review step. A per-message "转为任务草稿" action can prefill the existing form (user chooses project and edits title/goal, then explicitly saves). It must not silently create or start tasks or approve runs. The discussion UI and context preview are ready while the model interface awaits configuration. Existing five-node execution remains DEMO regardless of discussion-provider configuration.
