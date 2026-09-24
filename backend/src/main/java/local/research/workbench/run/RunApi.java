package local.research.workbench.run;

import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class RunApi {
    private final RunStore store;
    private final RunService service;
    private final RunEvents events;
    public RunApi(RunStore store,RunService service,RunEvents events) { this.store=store; this.service=service; this.events=events; }
    @GetMapping("/runs") public List<RunModels.Summary> list() { return store.list(); }
    @GetMapping("/runs/{id}") public RunModels.Detail detail(@PathVariable UUID id) { return store.detail(id.toString()); }
    @PostMapping("/tasks/{id}/runs")
    public RunModels.Detail start(@PathVariable UUID id,@RequestHeader(name="Idempotency-Key",required=false) String key) {
        return service.start(id.toString(),key);
    }
    @PostMapping("/runs/{id}/cancel")
    public RunModels.Detail cancel(@PathVariable UUID id) { return service.cancel(id.toString()); }
    @GetMapping(value="/runs/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id,@RequestHeader(name="Last-Event-ID",required=false) String cursor) {
        return events.subscribe(id.toString(),cursor);
    }
}
