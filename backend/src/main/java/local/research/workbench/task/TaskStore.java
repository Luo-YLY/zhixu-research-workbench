package local.research.workbench.task;

import static local.research.workbench.shared.SqlSupport.time;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TaskStore {
    private static final RowMapper<TaskApi.Task> MAPPER=(r,n)->new TaskApi.Task(r.getString("id"),r.getString("project_id"),
            r.getString("title"),r.getString("objective"),time(r,"created_at"));
    private final JdbcTemplate jdbc;
    public TaskStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<TaskApi.Task> list(String projectId) {
        return projectId==null?jdbc.query("SELECT * FROM research_task ORDER BY created_at DESC,id",MAPPER):
                jdbc.query("SELECT * FROM research_task WHERE project_id=? ORDER BY created_at DESC,id",MAPPER,projectId);
    }
    public void insert(TaskApi.Task task) {
        jdbc.update("INSERT INTO research_task(id,project_id,title,objective,created_at) VALUES (?,?,?,?,?)",
                task.id(),task.projectId(),task.title(),task.objective(),task.createdAt().atOffset(ZoneOffset.UTC));
    }
}
