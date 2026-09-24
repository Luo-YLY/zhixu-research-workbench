package local.research.workbench.run;

import static local.research.workbench.run.RunModels.*;
import static local.research.workbench.shared.SqlSupport.*;
import java.util.List;
import java.util.Set;
import local.research.workbench.shared.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RunStore {
    public static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "REJECTED");
    static final RowMapper<Summary> SUMMARY = (r,n) -> new Summary(r.getString("id"),r.getString("task_id"),
            r.getString("task_title"),r.getString("project_id"),r.getString("status"),r.getString("execution_mode"),
            time(r,"created_at"),time(r,"updated_at"));
    static final RowMapper<Approval> APPROVAL = (r,n) -> new Approval(r.getString("id"),r.getString("run_id"),
            r.getString("task_title"),r.getString("status"),time(r,"created_at"),r.getString("decision"),
            r.getString("comment"),time(r,"decided_at"));
    private final JdbcTemplate jdbc;
    public RunStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Summary> list() {
        return jdbc.query("SELECT * FROM workflow_run ORDER BY created_at DESC,id",SUMMARY);
    }
    public Summary lock(String id) {
        var rows = jdbc.query("SELECT * FROM workflow_run WHERE id=? FOR UPDATE",SUMMARY,id);
        if (rows.isEmpty()) throw ApiException.notFound("运行");
        return rows.getFirst();
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Detail detail(String id) {
        var runs = jdbc.query("SELECT * FROM workflow_run WHERE id=?",SUMMARY,id);
        if (runs.isEmpty()) throw ApiException.notFound("运行");
        var run = runs.getFirst();
        String objective = jdbc.queryForObject("SELECT objective FROM workflow_run WHERE id=?",String.class,id);
        var events = jdbc.query("SELECT * FROM run_event WHERE run_id=? ORDER BY id",(r,n) ->
                new Event(r.getString("id"),r.getString("run_id"),r.getString("event_type"),r.getString("message"),time(r,"created_at")),id);
        var artifacts = jdbc.query("SELECT * FROM artifact WHERE run_id=? ORDER BY created_at,id",(r,n) ->
                new Artifact(r.getString("id"),r.getString("run_id"),r.getString("name"),r.getString("media_type"),
                        r.getString("sha256"),r.getLong("size_bytes"),time(r,"created_at"),"/api/artifacts/"+r.getString("id")+"/download"),id);
        var approvals = jdbc.query("SELECT a.*,r.task_title FROM approval_request a JOIN workflow_run r ON r.id=a.run_id WHERE a.run_id=?",APPROVAL,id);
        return new Detail(run.id(),run.taskId(),run.taskTitle(),run.projectId(),run.status(),run.executionMode(),
                run.createdAt(),run.updatedAt(),objective,nodes(id),events,artifacts,approvals.isEmpty()?null:approvals.getFirst());
    }
    public List<Node> nodes(String id) {
        return jdbc.query("SELECT * FROM node_run WHERE run_id=? ORDER BY position",(r,n) -> new Node(r.getString("id"),
                r.getString("node_key"),r.getString("label"),r.getInt("position"),r.getString("status"),
                time(r,"started_at"),time(r,"finished_at"),r.getString("detail")),id);
    }
    public List<Approval> approvals() {
        return jdbc.query("SELECT a.*,r.task_title FROM approval_request a JOIN workflow_run r ON r.id=a.run_id ORDER BY a.created_at DESC,a.id",APPROVAL);
    }
    public void event(String id, String type, String message) {
        jdbc.update("INSERT INTO run_event(run_id,event_type,message,created_at) VALUES (?,?,?,?)",id,type,message,now());
        jdbc.update("UPDATE workflow_run SET updated_at=? WHERE id=?",now(),id);
    }
    public List<String> runnable() {
        return jdbc.queryForList("SELECT id FROM workflow_run WHERE status IN ('QUEUED','RUNNING') ORDER BY created_at LIMIT 10",String.class);
    }
}
