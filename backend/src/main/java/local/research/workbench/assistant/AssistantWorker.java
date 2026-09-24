package local.research.workbench.assistant;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="workbench.assistant.worker.enabled",havingValue="true",matchIfMissing=true)
public class AssistantWorker {
    private static final Logger LOG=LoggerFactory.getLogger(AssistantWorker.class);
    private final AssistantService service;
    private final AssistantGateway gateway;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final ExecutorService executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon(true).name("assistant-model-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public AssistantWorker(AssistantService service,AssistantGateway gateway) {this.service=service;this.gateway=gateway;}
    @Scheduled(fixedDelay=350,initialDelay=1000)
    public void tick() {
        if(!service.ready()||stopping.get()||!busy.compareAndSet(false,true))return;
        try {executor.execute(this::processNext);}catch(RejectedExecutionException e){busy.set(false);}
    }
    private void processNext() {
        AssistantModels.PendingTurn turn=null;
        try {
            turn=service.claim();if(turn==null)return;
            var captured=turn;
            if(stopping.get()||service.cancelled(turn.id()))return;
            String response=gateway.answer(turn.prompt(),()->stopping.get()||Thread.currentThread().isInterrupted()||service.cancelled(captured.id()));
            if(!stopping.get())service.complete(turn,response);
        } catch(Exception e) {
            LOG.warn("Assistant model invocation failed ({})",e.getClass().getSimpleName());
            if(turn!=null&&!stopping.get()) {
                String reason=e instanceof IllegalStateException?AssistantContext.clip(e.getMessage(),700):"请检查所选模型配置、网络及服务日志后重试。";
                service.fail(turn,"模型调用失败："+reason);
            }
        } finally {busy.set(false);}
    }
    @PreDestroy public void close() {stopping.set(true);executor.shutdownNow();}
}
