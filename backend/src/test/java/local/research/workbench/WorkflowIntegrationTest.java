package local.research.workbench;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import local.research.workbench.artifact.ArtifactStore;
import local.research.workbench.project.ProjectApi;
import local.research.workbench.run.*;
import local.research.workbench.shared.ApiException;
import local.research.workbench.task.TaskApi;
import local.research.workbench.workflow.DemoWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workflow-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workbench.worker.enabled=false","workbench.data-dir=target/test-artifacts"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class WorkflowIntegrationTest {
    @Autowired ProjectApi projects;
    @Autowired TaskApi tasks;
    @Autowired RunService runs;
    @Autowired RunStore store;
    @Autowired DemoWorkflow workflow;
    @Autowired ArtifactStore artifacts;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private TaskApi.Task task() {
        var project=projects.create(new ProjectApi.CreateProject("测试项目", "集成测试"));
        return tasks.create(new TaskApi.CreateTask(UUID.fromString(project.id()),"复现任务","仅验证DEMO研究工作流"));
    }
    private RunModels.Detail waiting() {
        var run=runs.start(task().id(),UUID.randomUUID().toString());
        for(int i=0;i<7;i++) workflow.advance(run.id());
        var waiting=store.detail(run.id());
        assertThat(waiting.status()).isEqualTo("WAITING_APPROVAL");
        return waiting;
    }
    @Test void approvalProducesRealVerifiableArtifactFromFrozenSnapshot() throws Exception {
        var waiting=waiting();
        assertThat(waiting.artifacts()).isEmpty();
        workflow.advance(waiting.id());
        assertThat(store.detail(waiting.id()).status()).isEqualTo("WAITING_APPROVAL");
        jdbc.update("UPDATE research_task SET objective='已变更的任务' WHERE id=?",waiting.taskId());
        runs.decide(waiting.approval().id(),"APPROVE","确认DEMO规范");
        workflow.advance(waiting.id()); workflow.advance(waiting.id());
        var complete=store.detail(waiting.id());
        assertThat(complete.status()).isEqualTo("COMPLETED");
        assertThat(complete.objective()).isEqualTo(waiting.objective());
        assertThat(complete.nodes()).allMatch(n->n.status().equals("SUCCEEDED"));
        var artifact=complete.artifacts().getFirst();
        byte[] bytes=artifacts.download(artifact.id()).bytes();
        assertThat(ArtifactStore.sha256(bytes)).isEqualTo(artifact.sha256());
        assertThat(bytes.length).isEqualTo(artifact.sizeBytes());
        assertThat(new String(bytes,StandardCharsets.UTF_8)).contains("DEMO","research_only","确认DEMO规范");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE entity_id=?",Long.class,complete.id())).isGreaterThanOrEqualTo(2);
        assertThatThrownBy(()->runs.cancel(complete.id())).isInstanceOf(ApiException.class);
        String relative=jdbc.queryForObject("SELECT relative_path FROM artifact WHERE id=?",String.class,artifact.id());
        Files.writeString(Path.of("target/test-artifacts/artifacts").resolve(relative),"tampered");
        assertThatThrownBy(()->artifacts.download(artifact.id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ARTIFACT_INTEGRITY_ERROR"));
    }
    @Test void concurrentSameIdempotencyKeyReturnsOneRun() throws Exception {
        String task=task().id(); String key=UUID.randomUUID().toString();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Callable<String> attempt=()->{start.await();return runs.start(task,key).id();};
            var first=pool.submit(attempt); var second=pool.submit(attempt); start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo(second.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE task_id=?",Long.class,task)).isEqualTo(1);
        var original=runs.start(task,key); runs.cancel(original.id());
        assertThat(runs.start(task,key).id()).isEqualTo(original.id());
        assertThat(runs.start(task,"new-attempt").id()).isNotEqualTo(original.id());
    }
    @Test void concurrentDifferentKeysCannotCreateDuplicateActiveRuns() throws Exception {
        String task=task().id();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Callable<Integer> attempt=()->{
                start.await();
                try { runs.start(task,UUID.randomUUID().toString()); return 200; }
                catch(ApiException e) { return e.status(); }
            };
            var first=pool.submit(attempt);var second=pool.submit(attempt);start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }
    @Test void competingApprovalDecisionsHaveExactlyOneWinner() throws Exception {
        var run=waiting(); String approval=run.approval().id();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Callable<Integer> attempt=()->{start.await();try {runs.decide(approval,"APPROVE","");return 200;}catch(ApiException e){return e.status();}};
            var first=pool.submit(attempt); var second=pool.submit(attempt); start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }
    @Test void cancellationRacingApprovalCannotResumeCancelledRun() throws Exception {
        var run=waiting();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var approval=pool.submit(()->{start.await();try {runs.decide(run.approval().id(),"APPROVE","");return 200;}catch(ApiException e){return e.status();}});
            var cancel=pool.submit(()->{start.await();return runs.cancel(run.id());}); start.countDown();
            assertThat(approval.get(10,TimeUnit.SECONDS)).isIn(200,409);
            assertThat(cancel.get(10,TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
        }
        workflow.advance(run.id());
        assertThat(store.detail(run.id()).status()).isEqualTo("CANCELLED");
        assertThat(store.detail(run.id()).artifacts()).isEmpty();
    }
    @Test void rejectRequiresReasonAndIsTerminal() {
        var run=waiting();
        assertThatThrownBy(()->runs.decide(run.approval().id(),"REJECT"," ")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(400));
        runs.decide(run.approval().id(),"REJECT","需要调整目标"); workflow.advance(run.id());
        assertThat(store.detail(run.id()).status()).isEqualTo("REJECTED");
        assertThat(store.detail(run.id()).artifacts()).isEmpty();
        assertThatThrownBy(()->runs.decide(run.approval().id(),"APPROVE","")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(409));
    }
    @Test void invalidRequestsAndForeignOriginHaveStructuredErrors() throws Exception {
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/projects").header("Origin","https://unrelated.example").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"blocked\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_FORBIDDEN"));
        mvc.perform(get("/api/runs/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/runs/"+UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void terminalSseReconnectIncludesPersistedEventHistory() throws Exception {
        var run=runs.start(task().id(),null); runs.cancel(run.id());
        var response=mvc.perform(get("/api/runs/"+run.id()+"/events").header("Last-Event-ID","0"))
                .andExpect(request().asyncStarted()).andReturn();
        response.getAsyncResult(5000);
        mvc.perform(asyncDispatch(response)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:run")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RUN_CANCELLED")));
    }
    @Test void hostileHostIsBlockedEvenWithMatchingOrigin() throws Exception {
        mvc.perform(get("/api/assistant/status").with(request->{request.setServerName("evil.example");return request;}))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_FORBIDDEN"));
        mvc.perform(post("/api/projects").with(request->{request.setServerName("evil.example");return request;})
                        .header("Origin","http://evil.example").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"blocked\"}"))
                .andExpect(status().isForbidden());
    }
    @Test void defaultAssistantIsUnconfiguredAndDoesNotQueueModelMessages() throws Exception {
        mvc.perform(get("/api/assistant/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("UNCONFIGURED")).andExpect(jsonPath("$.available").value(false));
        mvc.perform(get("/api/system")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0.5.0"))
                .andExpect(jsonPath("$.capabilities[0].status").value("CONFIGURATION_REQUIRED"));
        String response=mvc.perform(post("/api/assistant/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"未配置模型\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sessionId=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response).path("id").asText();
        mvc.perform(post("/api/assistant/sessions/"+sessionId+"/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"暂不连接模型\",\"includeContext\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_message WHERE session_id=?",Long.class,sessionId)).isZero();
    }
}
