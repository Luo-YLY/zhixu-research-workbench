package local.research.workbench.workflow;

import local.research.workbench.run.RunModels;

/** Future Codex/Python adapters implement an explicit execution contract at this boundary. */
public interface ResearchStepExecutor {
    String mode();
    String execute(String nodeKey, RunModels.Detail snapshot);
}
