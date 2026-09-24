package local.research.workbench.workflow;

import local.research.workbench.artifact.ArtifactStore;
import local.research.workbench.run.RunModels;
import org.springframework.stereotype.Component;

@Component
public class DemoStepExecutor implements ResearchStepExecutor {
    private final ArtifactStore artifacts;
    public DemoStepExecutor(ArtifactStore artifacts) { this.artifacts=artifacts; }
    @Override public String mode() { return "DEMO"; }
    @Override public String execute(String key,RunModels.Detail snapshot) {
        return switch(key) {
            case "TASK_SPEC" -> "已冻结标题、研究目标与demo-v1模板，research_only=true";
            case "EVIDENCE" -> "DEMO证据占位已建立；真实文献数量为0，未进行研究分析";
            case "VALIDATE" -> {
                if(snapshot.objective().isBlank() || snapshot.taskTitle().isBlank() || snapshot.nodes().size()!=5 || !"DEMO".equals(snapshot.executionMode()))
                    throw new IllegalStateException("Invalid DEMO task snapshot");
                yield "校验通过：任务目标非空、模板包含5个节点、DEMO与research_only标记齐全";
            }
            case "ARCHIVE" -> {
                if(snapshot.approval()==null || !"APPROVED".equals(snapshot.approval().status())) throw new IllegalStateException("Approval missing");
                artifacts.archive(snapshot);
                yield "已写入UTF-8研究清单并登记SHA-256；这是DEMO产物，不含真实研究结果";
            }
            default -> throw new IllegalStateException("Unsupported DEMO node: "+key);
        };
    }
}
