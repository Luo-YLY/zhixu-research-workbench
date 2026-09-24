package local.research.workbench.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectApi {
    public record Project(String id, String name, String description, Instant createdAt) {}
    public record CreateProject(@NotBlank @Size(max=120) String name, @Size(max=2000) String description) {}
    private final ProjectService service;
    public ProjectApi(ProjectService service) { this.service=service; }
    @GetMapping
    public List<Project> list() {
        return service.list();
    }
    @PostMapping
    public Project create(@Valid @RequestBody CreateProject request) {
        return service.create(request.name(),request.description());
    }
}
