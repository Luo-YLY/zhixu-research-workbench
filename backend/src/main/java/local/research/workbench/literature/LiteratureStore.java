package local.research.workbench.literature;

import static local.research.workbench.shared.SqlSupport.time;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiteratureStore {
    public record IndexedChunk(String chunkId,String documentId,String title,String fileName,int pageNumber,int chunkNumber,
                               String documentSha256,String chunkSha256,String content) {}
    private final JdbcTemplate jdbc;
    public LiteratureStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public boolean duplicate(String projectId,String sha256) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM literature_document WHERE project_id=? AND sha256=?",Long.class,projectId,sha256)>0;
    }
    public List<LiteratureApi.Document> list(String projectId) {
        return jdbc.query("SELECT * FROM literature_document WHERE project_id=? ORDER BY created_at DESC,id",(r,n)->new LiteratureApi.Document(
                r.getString("id"),r.getString("project_id"),r.getString("title"),r.getString("file_name"),
                r.getString("media_type"),r.getString("sha256"),r.getLong("size_bytes"),r.getInt("page_count"),
                r.getInt("chunk_count"),time(r,"created_at")),projectId);
    }
    public LiteratureApi.Document document(String id) {
        var result=jdbc.query("SELECT * FROM literature_document WHERE id=?",(r,n)->new LiteratureApi.Document(
                r.getString("id"),r.getString("project_id"),r.getString("title"),r.getString("file_name"),
                r.getString("media_type"),r.getString("sha256"),r.getLong("size_bytes"),r.getInt("page_count"),
                r.getInt("chunk_count"),time(r,"created_at")),id);
        return result.isEmpty()?null:result.getFirst();
    }
    public void insert(LiteratureApi.Document document,List<LiteratureChunks.Part> parts) {
        jdbc.update("INSERT INTO literature_document(id,project_id,title,file_name,media_type,sha256,size_bytes,page_count,chunk_count,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                document.id(),document.projectId(),document.title(),document.fileName(),document.mediaType(),document.sha256(),
                document.sizeBytes(),document.pageCount(),document.chunkCount(),document.createdAt().atOffset(ZoneOffset.UTC));
        int n=0;
        for(var part:parts) {
            String hash=local.research.workbench.artifact.ArtifactStore.sha256(part.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO literature_chunk(id,document_id,page_number,chunk_number,content,sha256) VALUES (?,?,?,?,?,?)",
                    local.research.workbench.shared.SqlSupport.id(),document.id(),part.page(),part.number(),part.text(),hash);
            n++;
        }
    }
    public List<IndexedChunk> chunks(String projectId,String documentId) {
        return jdbc.query("SELECT c.id AS chunk_id,c.document_id,d.title,d.file_name,c.page_number,c.chunk_number,d.sha256 AS document_sha256,c.sha256 AS chunk_sha256,c.content " +
                "FROM literature_chunk c JOIN literature_document d ON d.id=c.document_id WHERE d.project_id=? " +
                (documentId==null?"":"AND d.id=? ")+"ORDER BY d.created_at,c.page_number,c.chunk_number",
                (r,n)->new IndexedChunk(r.getString("chunk_id"),r.getString("document_id"),r.getString("title"),
                        r.getString("file_name"),r.getInt("page_number"),r.getInt("chunk_number"),
                        r.getString("document_sha256"),r.getString("chunk_sha256"),r.getString("content")),
                documentId==null?new Object[]{projectId}:new Object[]{projectId,documentId});
    }

    public IndexedChunk chunk(String projectId,String chunkId) {
        var rows=jdbc.query("SELECT c.id AS chunk_id,c.document_id,d.title,d.file_name,c.page_number,c.chunk_number,"
                        +"d.sha256 AS document_sha256,c.sha256 AS chunk_sha256,c.content "
                        +"FROM literature_chunk c JOIN literature_document d ON d.id=c.document_id "
                        +"WHERE d.project_id=? AND c.id=?",
                (r,n)->new IndexedChunk(r.getString("chunk_id"),r.getString("document_id"),r.getString("title"),
                        r.getString("file_name"),r.getInt("page_number"),r.getInt("chunk_number"),
                        r.getString("document_sha256"),r.getString("chunk_sha256"),r.getString("content")),
                projectId,chunkId);
        return rows.isEmpty()?null:rows.getFirst();
    }
}
