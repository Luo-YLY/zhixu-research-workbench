package local.research.workbench.audit;

import static local.research.workbench.shared.SqlSupport.now;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLog {
    private final JdbcTemplate jdbc;
    public AuditLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void record(String action, String entityId, String detail) {
        record(action,entityId,"LOCAL_USER",detail);
    }
    public void recordSystem(String action,String entityId,String detail) {
        record(action,entityId,"DEMO_WORKER",detail);
    }
    public void recordAssistant(String action,String entityId,String detail) {
        record(action,entityId,"ASSISTANT_WORKER",detail);
    }
    private void record(String action,String entityId,String actor,String detail) {
        jdbc.update("INSERT INTO audit_event(action,entity_id,actor,detail,created_at) VALUES (?,?,?,?,?)",
                action, entityId, actor, detail, now());
    }
}
