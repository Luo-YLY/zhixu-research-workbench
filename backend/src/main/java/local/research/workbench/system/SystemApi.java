package local.research.workbench.system;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import local.research.workbench.assistant.AssistantGateway;
import local.research.workbench.literature.LiteratureEmbeddings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SystemApi {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final AssistantGateway assistant;
    private final LiteratureEmbeddings embeddings;
    public SystemApi(JdbcTemplate jdbc,DataSource dataSource,AssistantGateway assistant,LiteratureEmbeddings embeddings) {
        this.jdbc=jdbc;this.dataSource=dataSource;this.assistant=assistant;this.embeddings=embeddings;
    }
    private Map<String,String> capability(String key,String label,String status,String description) {
        return Map.of("key",key,"label",label,"status",status,"description",description);
    }
    @GetMapping("/system")
    public Map<String,Object> system() throws SQLException {
        String database;
        try(var connection=dataSource.getConnection()) { database=connection.getMetaData().getDatabaseProductName(); }
        boolean assistantReady=assistant.status().available();
        boolean embeddingsReady=embeddings.available();
        String ragStatus=assistantReady&&embeddingsReady?"AVAILABLE":(assistantReady||embeddingsReady?"PARTIAL":"CONFIGURATION_REQUIRED");
        String ragDescription="文献入库、来源和页码引用、词法检索可用；"
                +(embeddingsReady?"双向中英语义检索已配置；":"双向中英语义检索待配置 embedding；")
                +(assistantReady?"双语对照翻译与证据回答已配置":"双语对照翻译与证据回答待配置模型");
        return Map.of("appName","研究工作台","version","0.5.0","database",database,"executionMode","DEMO",
                "capabilities",List.of(
                        capability("assistant","页面研究助手",assistantReady?"AVAILABLE":"CONFIGURATION_REQUIRED",
                                assistantReady?"上下文对话界面已就绪，提交时使用已配置模型；研究流程仍为DEMO":"上下文对话界面已就绪，模型接口待配置"),
                        capability("workflow","固定研究工作流","AVAILABLE","持久化五节点流程、重启恢复与状态监控"),
                        capability("approval","人工审批","AVAILABLE","核对任务规范后确认或退回"),
                        capability("artifact","产物归档","AVAILABLE","真实文件下载与SHA-256完整性校验"),
                        capability("codex","Codex研究执行器","PLANNED","后续接入研究工作流；独立于讨论助手，当前不执行真实研究步骤"),
                        capability("rag","文献与RAG",ragStatus,ragDescription),
                        capability("schedule","周期任务与每日计划","AVAILABLE","每天或每周生成待办，支持手动事项、完成记录和暂停；不会自动启动DEMO研究运行"),
                        capability("collaboration","多人及多Agent","PLANNED","当前仅供本机单用户使用")));
    }
    @GetMapping("/dashboard")
    public Map<String,Long> dashboard() {
        return Map.of("projectCount",count("SELECT COUNT(*) FROM project"),"taskCount",count("SELECT COUNT(*) FROM research_task"),
                "activeRunCount",count("SELECT COUNT(*) FROM workflow_run WHERE status IN ('QUEUED','RUNNING','WAITING_APPROVAL')"),
                "pendingApprovalCount",count("SELECT COUNT(*) FROM approval_request WHERE status='PENDING'"),
                "completedRunCount",count("SELECT COUNT(*) FROM workflow_run WHERE status='COMPLETED'"));
    }
    private long count(String sql) { return jdbc.queryForObject(sql,Long.class); }
}
