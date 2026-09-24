package local.research.workbench.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AssistantModels {
    private AssistantModels() {}
    public record ContextRequest(@Size(max=40) String page, UUID projectId, UUID taskId, UUID runId,
                                 @Size(max=40) String nodeKey, @Size(max=2000) String selectedText) {}
    public record ContextSnapshot(String title,String summary,String content) {}
    public record SessionSummary(String id,String title,String status,Instant createdAt,Instant updatedAt) {}
    public record Message(String id,String role,String content,String status,String contextTitle,String contextSummary,Instant createdAt) {}
    public record SessionDetail(String id,String title,String status,Instant createdAt,Instant updatedAt,List<Message> messages) {}
    public record NewSession(@Size(max=120) String title) {}
    public record SendMessage(@NotBlank @Size(max=6000) String text,boolean includeContext,@Valid ContextRequest context) {}
    public record PendingTurn(String id,String sessionId,String prompt) {}
}
