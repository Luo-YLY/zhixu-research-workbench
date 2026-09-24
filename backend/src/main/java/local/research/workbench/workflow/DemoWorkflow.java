package local.research.workbench.workflow;

import static local.research.workbench.shared.SqlSupport.*;
import local.research.workbench.audit.AuditLog;
import local.research.workbench.run.RunStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One durable transition per tick; no external commands or model calls are permitted. */
@Service
public class DemoWorkflow {
    private final JdbcTemplate jdbc;
    private final RunStore store;
    private final ResearchStepExecutor executor;
    private final AuditLog audit;
    public DemoWorkflow(JdbcTemplate jdbc, RunStore store, ResearchStepExecutor executor, AuditLog audit) {
        this.jdbc=jdbc; this.store=store; this.executor=executor; this.audit=audit;
    }
    @Transactional
    public void advance(String runId) {
        var run=store.lock(runId);
        if (!"RUNNING".equals(run.status()) && !"QUEUED".equals(run.status())) return;
        var nodes=store.nodes(runId);
        var next=nodes.stream().filter(n->!"SUCCEEDED".equals(n.status())).findFirst();
        if (next.isEmpty()) return;
        var node=next.get(); var time=now();
        if ("APPROVAL".equals(node.key())) {
            jdbc.update("UPDATE node_run SET status='WAITING_APPROVAL',started_at=?,detail='请核对冻结的任务目标；当前为DEMO流程' WHERE id=?",time,node.id());
            jdbc.update("UPDATE workflow_run SET status='WAITING_APPROVAL' WHERE id=?",runId);
            jdbc.update("INSERT INTO approval_request(id,run_id,status,created_at,comment) VALUES (?,?,?,?,?)",id(),runId,"PENDING",time,"");
            store.event(runId,"APPROVAL_REQUESTED","结构校验通过，等待人工审批；审批通过后才生成归档产物");
            audit.recordSystem("APPROVAL_REQUESTED",runId,"等待人工确认DEMO任务规范");
            return;
        }
        if ("PENDING".equals(node.status())) {
            jdbc.update("UPDATE workflow_run SET status='RUNNING' WHERE id=?",runId);
            jdbc.update("UPDATE node_run SET status='RUNNING',started_at=?,detail='正在执行确定性的DEMO处理' WHERE id=?",time,node.id());
            store.event(runId,"NODE_STARTED",node.label()+"开始");
            return;
        }
        if (!"RUNNING".equals(node.status())) return;
        String detail=executor.execute(node.key(),store.detail(runId));
        jdbc.update("UPDATE node_run SET status='SUCCEEDED',finished_at=?,detail=? WHERE id=?",time,detail,node.id());
        store.event(runId,"NODE_SUCCEEDED",node.label()+"完成："+detail);
        if ("ARCHIVE".equals(node.key())) {
            jdbc.update("UPDATE workflow_run SET status='COMPLETED' WHERE id=?",runId);
            store.event(runId,"RUN_COMPLETED","DEMO流程完成，产物可下载并核验");
            audit.recordSystem("RUN_COMPLETED",runId,"DEMO产物归档成功");
        }
    }
    @Transactional
    public void fail(String runId) {
        var run=store.lock(runId);
        if(RunStore.TERMINAL.contains(run.status())) return;
        jdbc.update("UPDATE workflow_run SET status='FAILED' WHERE id=?",runId);
        jdbc.update("UPDATE node_run SET status='FAILED',finished_at=?,detail='执行失败，请查看服务日志' WHERE run_id=? AND status='RUNNING'",now(),runId);
        jdbc.update("UPDATE node_run SET status='CANCELLED',finished_at=?,detail='前序节点失败' WHERE run_id=? AND status='PENDING'",now(),runId);
        store.event(runId,"RUN_FAILED","流程执行失败，可检查服务日志后建立新的运行");
        audit.recordSystem("RUN_FAILED",runId,"DEMO处理失败");
    }
}
