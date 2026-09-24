package local.research.workbench.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PlanApi {
    public record Schedule(String id, String taskId, String taskTitle, String frequency, Integer weekday,
                           LocalTime plannedTime, LocalDate startDate, boolean active, Instant createdAt) {}
    public record Item(String id, LocalDate date, LocalTime plannedTime, String taskId, String scheduleId,
                       String title, String status, Instant createdAt, Instant completedAt) {}
    public record CreateSchedule(@NotNull UUID taskId, @NotBlank String frequency, Integer weekday,
                                 @NotNull LocalTime plannedTime, @NotNull LocalDate startDate) {}
    public record CreateItem(@NotBlank @Size(max=180) String title, @NotNull LocalDate date, UUID taskId,
                             LocalTime plannedTime) {}
    private final PlanService service;
    public PlanApi(PlanService service) { this.service=service; }

    @GetMapping("/schedules") public List<Schedule> schedules() { return service.schedules(); }
    @PostMapping("/schedules") public Schedule createSchedule(@Valid @RequestBody CreateSchedule request) {
        return service.createSchedule(request);
    }
    @PostMapping("/schedules/{id}/pause") public Schedule pause(@PathVariable UUID id) { return service.setActive(id.toString(),false); }
    @PostMapping("/schedules/{id}/resume") public Schedule resume(@PathVariable UUID id) { return service.setActive(id.toString(),true); }
    @GetMapping("/plans") public List<Item> plans(@RequestParam(required=false) LocalDate date) { return service.plans(date); }
    @PostMapping("/plans") public Item createPlan(@Valid @RequestBody CreateItem request) { return service.createPlan(request); }
    @PostMapping("/plans/{id}/complete") public Item complete(@PathVariable UUID id) { return service.setDone(id.toString(),true); }
    @PostMapping("/plans/{id}/reopen") public Item reopen(@PathVariable UUID id) { return service.setDone(id.toString(),false); }
}
