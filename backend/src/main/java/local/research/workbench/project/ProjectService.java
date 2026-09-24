package local.research.workbench.project;

import static local.research.workbench.shared.SqlSupport.*;
import java.util.List;
import local.research.workbench.audit.AuditLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private final ProjectStore store;
    private final AuditLog audit;
    public ProjectService(ProjectStore store,AuditLog audit) { this.store=store;this.audit=audit; }
    public List<ProjectApi.Project> list() { return store.list(); }
    @Transactional
    public ProjectApi.Project create(String name,String description) {
        var project=new ProjectApi.Project(id(),name.strip(),description==null?"":description.strip(),now().toInstant());
        store.insert(project); audit.record("PROJECT_CREATED",project.id(),"创建项目");
        return project;
    }
}
