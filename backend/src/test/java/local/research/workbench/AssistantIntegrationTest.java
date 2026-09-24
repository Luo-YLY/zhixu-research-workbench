package local.research.workbench;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import local.research.workbench.assistant.*;
import local.research.workbench.assistant.AssistantModels.*;
import local.research.workbench.project.ProjectApi;
import local.research.workbench.shared.ApiException;
import local.research.workbench.task.TaskApi;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:assistant-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workbench.worker.enabled=false","workbench.assistant.worker.enabled=false","workbench.data-dir=target/assistant-artifacts"})
@ActiveProfiles("local")
@Import(AssistantIntegrationTest.FakeConfiguration.class)
class AssistantIntegrationTest {
    @TestConfiguration static class FakeConfiguration {
        @Bean @Primary FakeGateway fakeGateway() {return new FakeGateway();}
    }
    static class FakeGateway implements AssistantGateway {
        record Call(String prompt,BooleanSupplier cancelled,CompletableFuture<String> answer) {}
        final BlockingQueue<Call> calls=new LinkedBlockingQueue<>();
        final AtomicInteger count=new AtomicInteger();
        final CopyOnWriteArrayList<Call> all=new CopyOnWriteArrayList<>();
        @Override public Availability status(){return new Availability("CODEX_CLI",true,"test-fake","仅测试替身");}
        @Override public String answer(String prompt,BooleanSupplier cancelled) throws Exception {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            count.incrementAndGet();var call=new Call(prompt,cancelled,new CompletableFuture<>());all.add(call);calls.add(call);
            return call.answer().get(15,TimeUnit.SECONDS);
        }
        Call take() throws InterruptedException {var call=calls.poll(5,TimeUnit.SECONDS);assertThat(call).isNotNull();return call;}
        void reset(){all.forEach(c->c.answer().complete("测试结束"));all.clear();calls.clear();count.set(0);}
    }
    @Autowired AssistantService service;
    @Autowired AssistantStore store;
    @Autowired AssistantContext contexts;
    @Autowired FakeGateway gateway;
    @Autowired ProjectApi projects;
    @Autowired TaskApi tasks;
    @Autowired JdbcTemplate jdbc;
    private AssistantWorker worker;
    @BeforeEach void setUp(){service.recoverStartup();gateway.reset();worker=new AssistantWorker(service,gateway);}
    @AfterEach void tearDown(){worker.close();gateway.reset();service.recoverStartup();}
    private SessionDetail session(){return service.create("研究测试");}
    private SendMessage plain(String text){return new SendMessage(text,false,null);}
    private SessionDetail await(String id,String messageStatus) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){var detail=store.detail(id);if(detail.messages().getLast().status().equals(messageStatus))return detail;Thread.sleep(20);}
        throw new AssertionError("Expected "+messageStatus+", got "+store.detail(id));
    }
    @Test void capturesAuthoritativeContextAtSendAndNeverStartsModelOnPreview() throws Exception {
        var project=projects.create(new ProjectApi.CreateProject("研究项目","公开描述"));
        var task=tasks.create(new TaskApi.CreateTask(UUID.fromString(project.id()),"原标题","原始目标"));
        var context=new ContextRequest("tasks",UUID.fromString(project.id()),UUID.fromString(task.id()),null,null,"用户明确引用的文本");
        var preview=contexts.build(context);
        assertThat(preview.content()).contains("原标题","原始目标","DEMO");
        assertThat(gateway.count).hasValue(0);
        var session=session();service.send(session.id(),new SendMessage("解释当前任务",true,context),"capture");
        jdbc.update("UPDATE research_task SET title='变更后的标题',objective='之后修改的目标' WHERE id=?",task.id());
        worker.tick();var call=gateway.take();
        assertThat(call.prompt()).contains("原标题","原始目标","用户明确引用的文本").doesNotContain("变更后的标题","之后修改的目标");
        assertThat(jdbc.queryForObject("SELECT context_content FROM assistant_message WHERE session_id=? AND role='USER'",String.class,session.id())).contains("原始目标");
        call.answer().complete("明确的测试答复");
        var completed=await(session.id(),"COMPLETED");assertThat(completed.status()).isEqualTo("IDLE");
        assertThat(completed.messages().getLast().content()).isEqualTo("明确的测试答复");
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_event WHERE action='ASSISTANT_COMPLETED' AND entity_id=?",String.class,completed.messages().getLast().id())).isEqualTo("ASSISTANT_WORKER");
    }
    @Test void rejectsCrossProjectReferencesAndBoundsContext() {
        var first=projects.create(new ProjectApi.CreateProject("第一项目",""));var second=projects.create(new ProjectApi.CreateProject("第二项目",""));
        var task=tasks.create(new TaskApi.CreateTask(UUID.fromString(first.id()),"任务","目标"));
        var bad=new ContextRequest("tasks",UUID.fromString(second.id()),UUID.fromString(task.id()),null,null,null);
        assertThatThrownBy(()->contexts.build(bad)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CONTEXT_MISMATCH"));
        var noisy=new ContextRequest("tasks",UUID.fromString(first.id()),UUID.fromString(task.id()),null,null,"\u0001".repeat(2000));
        assertThat(contexts.build(noisy).content().length()).isLessThanOrEqualTo(12000);
        assertThat(gateway.count).hasValue(0);
    }
    @Test void idempotencyAndConcurrentGlobalCapacityPreventDuplicateCalls() throws Exception {
        var first=session();var second=session();var request=plain("同一请求");
        service.send(first.id(),request,"same-key");
        assertThat(service.send(first.id(),request,"same-key").messages()).hasSize(2);
        assertThatThrownBy(()->service.send(first.id(),plain("不同内容"),"same-key")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(()->service.send(first.id(),request,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(409));
        assertThatThrownBy(()->service.send(second.id(),request,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(429));
        service.cancel(first.id());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();try{service.send(first.id(),plain("a"),null);return 200;}catch(ApiException e){return e.status();}});
            var b=pool.submit(()->{start.await();try{service.send(second.id(),plain("b"),null);return 200;}catch(ApiException e){return e.status();}});
            start.countDown();assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,429);
        }
        assertThat(gateway.count).hasValue(0);
    }
    @Test void cancelledLateReplyCannotOverwriteNewTurn() throws Exception {
        var session=session();service.send(session.id(),plain("第一条"),null);worker.tick();var call=gateway.take();
        service.cancel(session.id());assertThat(call.cancelled().getAsBoolean()).isTrue();
        service.send(session.id(),plain("第二条"),null);
        call.answer().complete("迟到的答复");
        // A finished old callback must not switch the newer queued turn to IDLE.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(gateway.count.get()<2&&System.nanoTime()<deadline){worker.tick();Thread.sleep(20);}
        var second=gateway.take();
        var current=store.detail(session.id());assertThat(current.status()).isEqualTo("RUNNING");
        assertThat(current.messages().get(1).status()).isEqualTo("CANCELLED");
        assertThat(current.messages().get(1).content()).doesNotContain("迟到");
        second.answer().complete("第二条答复");await(session.id(),"COMPLETED");
    }
    @Test void contextualConcurrentAdmissionDoesNotExhaustConnectionPool() throws Exception {
        var project=projects.create(new ProjectApi.CreateProject("并发上下文","公开描述"));
        var request=new SendMessage("读取项目上下文",true,new ContextRequest("projects",UUID.fromString(project.id()),null,null,null,null));
        var sessions=new ArrayList<String>();for(int i=0;i<12;i++)sessions.add(session().id());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Integer>>();
            for(String session:sessions)futures.add(pool.submit(()->{start.await();try{service.send(session,request,null);return 200;}catch(ApiException e){return e.status();}}));
            start.countDown();var results=new ArrayList<Integer>();for(var future:futures)results.add(future.get(10,TimeUnit.SECONDS));
            assertThat(results.stream().filter(code->code==200).count()).isEqualTo(1);
            assertThat(results.stream().filter(code->code==429).count()).isEqualTo(11);
        }
        assertThat(gateway.count).hasValue(0);
    }
    @Test void providerFailureIsExplicitAndResponsesAreBounded() throws Exception {
        var first=session();service.send(first.id(),plain("故障测试"),null);worker.tick();
        gateway.take().answer().completeExceptionally(new IllegalStateException("测试故障"));
        assertThat(await(first.id(),"FAILED").messages().getLast().content()).contains("模型调用失败");
        var second=session();service.send(second.id(),plain("长度测试"),null);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(gateway.count.get()<2&&System.nanoTime()<deadline){worker.tick();Thread.sleep(20);}
        gateway.take().answer().complete("文".repeat(25000));
        assertThat(await(second.id(),"COMPLETED").messages().getLast().content()).hasSize(24000);
    }
    @Test void restartMarksBothQueuedAndRunningInterruptedWithoutReplay() {
        var queued=session();service.send(queued.id(),plain("排队请求"),null);
        service.recoverStartup();
        assertThat(store.detail(queued.id()).messages().getLast().status()).isEqualTo("INTERRUPTED");
        assertThat(store.detail(queued.id()).status()).isEqualTo("IDLE");
        var running=session();service.send(running.id(),plain("运行请求"),null);service.claim();
        service.recoverStartup();
        assertThat(store.detail(running.id()).messages().getLast().status()).isEqualTo("INTERRUPTED");
        assertThat(service.claim()).isNull();assertThat(gateway.count).hasValue(0);
    }
}
