package local.research.workbench.run;

import static local.research.workbench.shared.SqlSupport.*;
import java.util.List;
import local.research.workbench.audit.AuditLog;
import local.research.workbench.shared.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunService {
    private static final String[][] TEMPLATE = {{"TASK_SPEC","任务规范"},{"EVIDENCE","证据准备"},
            {"VALIDATE","结构校验"},{"APPROVAL","人工审批"},{"ARCHIVE","产物归档"}};
    private final JdbcTemplate jdbc;
    private final RunStore store;
    private final AuditLog audit;
    public RunService(JdbcTemplate jdbc, RunStore store, AuditLog audit) { this.jdbc=jdbc; this.store=store; this.audit=audit; }
    /** A task row serializes all starts; idempotency keys are scoped to that task. */
    @Transactional
    public RunModels.Detail start(String taskId, String key) {
        if (key != null && (key.isBlank() || key.length()>128 || !key.matches("[A-Za-z0-9._:-]+")))
            throw new ApiException(400,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key须为1至128位字母、数字或._:-");
        var tasks = jdbc.queryForList("SELECT * FROM research_task WHERE id=? FOR UPDATE",taskId);
        if (tasks.isEmpty()) throw ApiException.notFound("任务");
        if (key != null) {
            List<String> prior = jdbc.queryForList("SELECT id FROM workflow_run WHERE task_id=? AND idempotency_key=?",String.class,taskId,key);
            if (!prior.isEmpty()) return store.detail(prior.getFirst());
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE task_id=? AND status IN ('QUEUED','RUNNING','WAITING_APPROVAL')",Long.class,taskId)>0)
            throw new ApiException(409,"ACTIVE_RUN_EXISTS","此任务已有进行中的运行");
        var task=tasks.getFirst(); String runId=id(); var time=now();
        jdbc.update("INSERT INTO workflow_run(id,task_id,project_id,task_title,objective,workflow_version,status,execution_mode,idempotency_key,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                runId,taskId,task.get("project_id"),task.get("title"),task.get("objective"),"demo-v1","QUEUED","DEMO",key,time,time);
        for (int n=0;n<TEMPLATE.length;n++) {
            jdbc.update("INSERT INTO node_run(id,run_id,node_key,label,position,status,detail) VALUES (?,?,?,?,?,?,?)",
                    id(),runId,TEMPLATE[n][0],TEMPLATE[n][1],n+1,"PENDING","");
        }
        store.event(runId,"RUN_CREATED","DEMO运行已建立；任务规范已冻结，未接入真实研究或模型");
        audit.record("RUN_CREATED",runId,"workflow=demo-v1; task="+taskId);
        return store.detail(runId);
    }
    @Transactional
    public RunModels.Detail cancel(String runId) {
        var run=store.lock(runId);
        if (RunStore.TERMINAL.contains(run.status())) throw ApiException.conflict("已结束的运行不能取消");
        var time=now();
        jdbc.update("UPDATE workflow_run SET status='CANCELLED',updated_at=? WHERE id=?",time,runId);
        jdbc.update("UPDATE node_run SET status='CANCELLED',finished_at=?,detail='用户取消运行' WHERE run_id=? AND status IN ('PENDING','RUNNING','WAITING_APPROVAL')",time,runId);
        jdbc.update("UPDATE approval_request SET status='CANCELLED',comment='运行已取消',decided_at=? WHERE run_id=? AND status='PENDING'",time,runId);
        store.event(runId,"RUN_CANCELLED","用户取消运行"); audit.record("RUN_CANCELLED",runId,"用户取消");
        return store.detail(runId);
    }
    @Transactional
    public RunModels.Detail decide(String approvalId, String decision, String comment) {
        if (!List.of("APPROVE","REJECT").contains(decision)) throw new ApiException(400,"INVALID_DECISION","decision须为APPROVE或REJECT");
        if ("REJECT".equals(decision) && comment.isBlank()) throw new ApiException(400,"COMMENT_REQUIRED","退回时请填写原因");
        var ids=jdbc.queryForList("SELECT run_id FROM approval_request WHERE id=?",String.class,approvalId);
        if(ids.isEmpty()) throw ApiException.notFound("审批");
        String runId=ids.getFirst(); var run=store.lock(runId);
        String state=jdbc.queryForObject("SELECT status FROM approval_request WHERE id=?",String.class,approvalId);
        if (!"WAITING_APPROVAL".equals(run.status()) || !"PENDING".equals(state)) throw ApiException.conflict("此审批已处理或运行已结束");
        boolean approved="APPROVE".equals(decision); var time=now();
        jdbc.update("UPDATE approval_request SET status=?,decision=?,comment=?,decided_at=? WHERE id=?",
                approved?"APPROVED":"REJECTED",decision,comment.strip(),time,approvalId);
        jdbc.update("UPDATE node_run SET status=?,finished_at=?,detail=? WHERE run_id=? AND node_key='APPROVAL'",
                approved?"SUCCEEDED":"REJECTED",time,approved?"人工确认DEMO规范通过":"人工退回："+comment.strip(),runId);
        jdbc.update("UPDATE workflow_run SET status=?,updated_at=? WHERE id=?",approved?"RUNNING":"REJECTED",time,runId);
        if (!approved) jdbc.update("UPDATE node_run SET status='CANCELLED',finished_at=?,detail='审批退回，后续节点不执行' WHERE run_id=? AND status='PENDING'",time,runId);
        store.event(runId,approved?"APPROVAL_APPROVED":"APPROVAL_REJECTED",approved?"人工审批通过，继续归档":"人工退回："+comment.strip());
        audit.record("APPROVAL_"+decision,approvalId,"run="+runId+"; "+comment.strip());
        return store.detail(runId);
    }
}
