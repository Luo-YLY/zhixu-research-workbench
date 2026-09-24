package local.research.workbench.run;

import java.time.Instant;
import java.util.List;

public final class RunModels {
    private RunModels() {}
    public record Summary(String id, String taskId, String taskTitle, String projectId, String status,
                          String executionMode, Instant createdAt, Instant updatedAt) {}
    public record Node(String id, String key, String label, int position, String status,
                       Instant startedAt, Instant finishedAt, String detail) {}
    public record Event(String id, String runId, String type, String message, Instant createdAt) {}
    public record Approval(String id, String runId, String taskTitle, String status, Instant createdAt,
                           String decision, String comment, Instant decidedAt) {}
    public record Artifact(String id, String runId, String name, String mediaType, String sha256,
                           long sizeBytes, Instant createdAt, String downloadUrl) {}
    public record Detail(String id, String taskId, String taskTitle, String projectId, String status,
                         String executionMode, Instant createdAt, Instant updatedAt, String objective,
                         List<Node> nodes, List<Event> events, List<Artifact> artifacts, Approval approval) {}
}
