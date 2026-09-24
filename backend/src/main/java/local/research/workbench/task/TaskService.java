package local.research.workbench.task;

import static local.research.workbench.shared.SqlSupport.*;
import java.util.List;
import local.research.workbench.audit.AuditLog;
import local.research.workbench.project.ProjectStore;
import local.research.workbench.shared.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
    private final TaskStore store;
    private final ProjectStore projects;
    private final AuditLog audit;
    public TaskService(TaskStore store,ProjectStore projects,AuditLog audit) { this.store=store;this.projects=projects;this.audit=audit; }
    public List<TaskApi.Task> list(String projectId) { return store.list(projectId); }
    @Transactional
    public TaskApi.Task create(String projectId,String title,String objective) {
        if(!projects.exists(projectId)) throw ApiException.notFound("项目");
        var task=new TaskApi.Task(id(),projectId,title.strip(),objective.strip(),now().toInstant());
        store.insert(task); audit.record("TASK_CREATED",task.id(),"创建研究任务");
        return task;
    }
}
