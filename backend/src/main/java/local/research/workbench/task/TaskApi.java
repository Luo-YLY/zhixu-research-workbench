package local.research.workbench.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
public class TaskApi {
    public record Task(String id, String projectId, String title, String objective, Instant createdAt) {}
    public record CreateTask(@NotNull UUID projectId, @NotBlank @Size(max=180) String title,
                             @NotBlank @Size(max=4000) String objective) {}
    private final TaskService service;
    public TaskApi(TaskService service) { this.service=service; }
    @GetMapping
    public List<Task> list(@RequestParam(required=false) UUID projectId) {
        return service.list(projectId==null?null:projectId.toString());
    }
    @PostMapping
    public Task create(@Valid @RequestBody CreateTask request) {
        return service.create(request.projectId().toString(),request.title(),request.objective());
    }
}
