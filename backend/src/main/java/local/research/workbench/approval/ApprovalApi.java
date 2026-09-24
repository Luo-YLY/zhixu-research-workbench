package local.research.workbench.approval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import local.research.workbench.run.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalApi {
    public record Decision(@NotBlank String decision,@Size(max=4000) String comment) {}
    private final RunStore store;
    private final RunService service;
    public ApprovalApi(RunStore store,RunService service) { this.store=store; this.service=service; }
    @GetMapping public List<RunModels.Approval> list() { return store.approvals(); }
    @PostMapping("/{id}/decisions")
    public RunModels.Detail decide(@PathVariable UUID id,@Valid @RequestBody Decision request) {
        return service.decide(id.toString(),request.decision(),request.comment()==null?"":request.comment());
    }
}
