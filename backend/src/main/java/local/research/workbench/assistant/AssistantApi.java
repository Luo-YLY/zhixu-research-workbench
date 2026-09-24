package local.research.workbench.assistant;

import static local.research.workbench.assistant.AssistantModels.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
public class AssistantApi {
    private final AssistantGateway gateway;
    private final AssistantContext context;
    private final AssistantStore store;
    private final AssistantService service;
    public AssistantApi(AssistantGateway gateway,AssistantContext context,AssistantStore store,AssistantService service) {
        this.gateway=gateway;this.context=context;this.store=store;this.service=service;
    }
    @GetMapping("/status") public AssistantGateway.Availability status() {return gateway.status();}
    @PostMapping("/context") public ContextSnapshot context(@Valid @RequestBody ContextRequest request) {return context.build(request);}
    @GetMapping("/sessions") public List<SessionSummary> sessions() {return store.list();}
    @PostMapping("/sessions") public SessionDetail create(@Valid @RequestBody NewSession request) {return service.create(request.title());}
    @GetMapping("/sessions/{id}") public SessionDetail detail(@PathVariable UUID id) {return store.detail(id.toString());}
    @PostMapping("/sessions/{id}/messages") public SessionDetail send(@PathVariable UUID id,@Valid @RequestBody SendMessage request,
            @RequestHeader(name="Idempotency-Key",required=false) String key) {return service.send(id.toString(),request,key);}
    @PostMapping("/sessions/{id}/cancel") public SessionDetail cancel(@PathVariable UUID id) {return service.cancel(id.toString());}
}
