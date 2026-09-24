package local.research.workbench.project;

import static local.research.workbench.shared.SqlSupport.time;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectStore {
    private final JdbcTemplate jdbc;
    public ProjectStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<ProjectApi.Project> list() {
        return jdbc.query("SELECT * FROM project ORDER BY created_at DESC,id",(r,n)->new ProjectApi.Project(
                r.getString("id"),r.getString("name"),r.getString("description"),time(r,"created_at")));
    }
    public boolean exists(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM project WHERE id=?",Long.class,id)>0; }
    public void insert(ProjectApi.Project project) {
        jdbc.update("INSERT INTO project(id,name,description,created_at) VALUES (?,?,?,?)",
                project.id(),project.name(),project.description(),project.createdAt().atOffset(ZoneOffset.UTC));
    }
}
