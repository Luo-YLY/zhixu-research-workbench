package local.research.workbench.artifact;

import static local.research.workbench.shared.SqlSupport.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import local.research.workbench.run.RunModels;
import local.research.workbench.shared.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ArtifactStore {
    public record Download(String name, String mediaType, byte[] bytes) {}
    private final Path root;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public ArtifactStore(@Value("${workbench.data-dir}") String directory, JdbcTemplate jdbc) {
        root=Path.of(directory).toAbsolutePath().normalize().resolve("artifacts"); this.jdbc=jdbc;
    }
    /** Deterministic content and atomic replacement make interrupted DEMO archives repeatable. */
    public void archive(RunModels.Detail run) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM artifact WHERE run_id=?",Long.class,run.id())>0) return;
        Map<String,Object> manifest=new LinkedHashMap<>();
        manifest.put("schemaVersion","1.0"); manifest.put("workflowVersion","demo-v1");
        manifest.put("executionMode","DEMO"); manifest.put("research_only",true);
        manifest.put("notice","仅验证任务流程、人工审批与产物存储。未分析真实文献、未执行回测、未调用模型。");
        manifest.put("runId",run.id()); manifest.put("taskId",run.taskId());
        manifest.put("taskTitle",run.taskTitle()); manifest.put("objective",run.objective());
        manifest.put("runCreatedAt",run.createdAt().toString());
        manifest.put("approval",Map.of("id",run.approval().id(),"decision",run.approval().decision(),
                "comment",run.approval().comment(),"decidedAt",run.approval().decidedAt().toString()));
        manifest.put("evidence",Map.of("kind","DEMO_PLACEHOLDER","sourceDocuments",0,"realResearchPerformed",false));
        byte[] bytes=mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        String relative=run.id()+"/research-manifest.json";
        Path destination=root.resolve(relative).normalize();
        try {
            Files.createDirectories(destination.getParent());
            Path temporary=Files.createTempFile(destination.getParent(),"manifest-",".tmp");
            try {
                Files.write(temporary,bytes);
                Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch(IOException e) { throw new IllegalStateException("Cannot persist artifact",e); }
        jdbc.update("INSERT INTO artifact(id,run_id,name,media_type,sha256,size_bytes,relative_path,created_at) VALUES (?,?,?,?,?,?,?,?)",
                id(),run.id(),"research-manifest.json","application/json",sha256(bytes),bytes.length,relative,now());
    }
    public Download download(String artifactId) {
        var rows=jdbc.queryForList("SELECT * FROM artifact WHERE id=?",artifactId);
        if(rows.isEmpty()) throw ApiException.notFound("产物");
        var row=rows.getFirst(); Path path=root.resolve((String)row.get("relative_path")).normalize();
        if (!path.startsWith(root)) throw new ApiException(409,"ARTIFACT_INTEGRITY_ERROR","产物路径校验失败");
        try {
            if (!path.toRealPath().startsWith(root.toRealPath()) || Files.size(path)>1024*1024)
                throw new ApiException(409,"ARTIFACT_INTEGRITY_ERROR","产物文件校验失败");
            byte[] bytes=Files.readAllBytes(path);
            if (!sha256(bytes).equals(row.get("sha256")) || bytes.length!=((Number)row.get("size_bytes")).longValue())
                throw new ApiException(409,"ARTIFACT_INTEGRITY_ERROR","产物已改变，完整性校验失败");
            return new Download((String)row.get("name"),(String)row.get("media_type"),bytes);
        } catch(NoSuchFileException e) { throw new ApiException(409,"ARTIFACT_MISSING","产物记录存在，但文件缺失");
        } catch(IOException e) { throw new ApiException(409,"ARTIFACT_UNREADABLE","产物文件暂时无法读取"); }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
