package local.research.workbench.run;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import local.research.workbench.shared.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded streams and send workers; reconnect gets the latest snapshot including persisted history. */
@Service
public class RunEvents {
    private static final int MAX_STREAMS=20;
    private final ConcurrentMap<SseEmitter,Client> clients=new ConcurrentHashMap<>();
    private final Semaphore slots=new Semaphore(MAX_STREAMS);
    private final ThreadPoolExecutor sender=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(MAX_STREAMS),
            Thread.ofPlatform().daemon(true).name("sse-send-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final RunStore store;
    public RunEvents(RunStore store) { this.store=store; }
    private static class Client {
        final String runId; final AtomicBoolean sending=new AtomicBoolean();
        String cursor; long lastHeartbeat=System.currentTimeMillis();
        Client(String runId,String cursor) { this.runId=runId; this.cursor=cursor; }
    }
    public SseEmitter subscribe(String id,String cursor) {
        store.detail(id);
        if(cursor!=null && !cursor.matches("[0-9]{1,19}")) throw new ApiException(400,"INVALID_CURSOR","Last-Event-ID格式无效");
        if(!slots.tryAcquire()) throw new ApiException(429,"STREAM_LIMIT","实时连接数量已达上限，请关闭多余页面");
        SseEmitter emitter=new SseEmitter(30*60*1000L);
        clients.put(emitter,new Client(id,cursor));
        emitter.onCompletion(()->remove(emitter)); emitter.onTimeout(()->{remove(emitter);emitter.complete();});
        emitter.onError(e->remove(emitter));
        dispatch(emitter,clients.get(emitter));
        return emitter;
    }
    private void remove(SseEmitter emitter) { if(clients.remove(emitter)!=null) slots.release(); }
    @Scheduled(fixedDelay=1000)
    public void tick() { clients.forEach(this::dispatch); }
    private void dispatch(SseEmitter emitter,Client client) {
        if(client==null || !client.sending.compareAndSet(false,true)) return;
        try { sender.execute(()->send(emitter,client)); }
        catch(RejectedExecutionException e) { client.sending.set(false); }
    }
    private void send(SseEmitter emitter,Client client) {
        try {
            var detail=store.detail(client.runId);
            String latest=detail.events().isEmpty()?"0":detail.events().getLast().id();
            boolean terminal=RunStore.TERMINAL.contains(detail.status());
            if(!latest.equals(client.cursor) || terminal) {
                emitter.send(SseEmitter.event().name("run").id(latest).reconnectTime(2000).data(detail));
                client.cursor=latest;
            }
            if(terminal) { remove(emitter); emitter.complete(); }
            else if(System.currentTimeMillis()-client.lastHeartbeat>=15000) {
                emitter.send(SseEmitter.event().comment("heartbeat")); client.lastHeartbeat=System.currentTimeMillis();
            }
        } catch(IOException|IllegalStateException e) { remove(emitter); emitter.completeWithError(e);
        } catch(Exception e) { remove(emitter); emitter.completeWithError(e);
        } finally { client.sending.set(false); }
    }
    @PreDestroy
    public void close() {
        clients.keySet().forEach(SseEmitter::complete); clients.clear(); sender.shutdownNow();
    }
}
