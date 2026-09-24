package local.research.workbench.assistant;

import static local.research.workbench.assistant.AssistantModels.*;
import static local.research.workbench.shared.SqlSupport.*;
import java.util.List;
import local.research.workbench.shared.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AssistantStore {
    private final JdbcTemplate jdbc;
    private static final RowMapper<SessionSummary> SESSION=(r,n)->new SessionSummary(r.getString("id"),r.getString("title"),r.getString("status"),time(r,"created_at"),time(r,"updated_at"));
    public AssistantStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public void globalLock() {jdbc.queryForObject("SELECT id FROM assistant_runtime_lock WHERE id=1 FOR UPDATE",Integer.class);}
    public List<SessionSummary> list() {return jdbc.query("SELECT * FROM assistant_session ORDER BY updated_at DESC,id",SESSION);}
    public SessionSummary session(String id) {
        var result=jdbc.query("SELECT * FROM assistant_session WHERE id=?",SESSION,id);
        if(result.isEmpty())throw ApiException.notFound("助手会话");return result.getFirst();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SessionDetail detail(String id) {
        var session=session(id);
        var messages=jdbc.query("SELECT * FROM assistant_message WHERE session_id=? ORDER BY message_order",(r,n)->
                new Message(r.getString("id"),r.getString("role"),r.getString("content"),r.getString("status"),r.getString("context_title"),r.getString("context_summary"),time(r,"created_at")),id);
        return new SessionDetail(id,session.title(),session.status(),session.createdAt(),session.updatedAt(),messages);
    }
    public boolean occupied() {return jdbc.queryForObject("SELECT COUNT(*) FROM assistant_message WHERE role='ASSISTANT' AND status IN ('QUEUED','RUNNING')",Long.class)>0;}
    public boolean active(String messageId) {return jdbc.queryForObject("SELECT COUNT(*) FROM assistant_message WHERE id=? AND status IN ('QUEUED','RUNNING')",Long.class,messageId)>0;}
    public List<Message> history(String sessionId) {
        return jdbc.query("SELECT * FROM assistant_message WHERE session_id=? AND status='COMPLETED' ORDER BY message_order DESC LIMIT 8",(r,n)->
                new Message(r.getString("id"),r.getString("role"),r.getString("content"),r.getString("status"),r.getString("context_title"),r.getString("context_summary"),time(r,"created_at")),sessionId).reversed();
    }
    public void insertMessage(String id,String sessionId,String turnId,String role,String content,String status,ContextSnapshot context,String prompt,String key,String hash) {
        jdbc.update("INSERT INTO assistant_message(id,session_id,turn_id,role,content,status,context_title,context_summary,context_content,prompt_content,idempotency_key,request_hash,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,sessionId,turnId,role,content,status,context.title(),context.summary(),context.content(),prompt,key,hash,now());
    }
    public void touch(String sessionId,String status) {jdbc.update("UPDATE assistant_session SET status=?,updated_at=? WHERE id=?",status,now(),sessionId);}
}
