package local.research.workbench.literature;

import static local.research.workbench.shared.SqlSupport.now;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiteratureEmbeddingStore {
    public record Stored(String chunkId,String chunkSha256,float[] vector) {}
    private final JdbcTemplate jdbc;
    public LiteratureEmbeddingStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Map<String,Stored> load(String projectId,String documentId,String modelId) {
        String sql="SELECT e.chunk_id,e.chunk_sha256,e.dimension,e.vector_base64 FROM literature_embedding e "
                +"JOIN literature_chunk c ON c.id=e.chunk_id JOIN literature_document d ON d.id=c.document_id "
                +"WHERE d.project_id=? AND e.model_id=? "+(documentId==null?"":"AND d.id=? ");
        Object[] args=documentId==null?new Object[]{projectId,modelId}:new Object[]{projectId,modelId,documentId};
        List<Stored> rows=jdbc.query(sql,(r,n)->new Stored(r.getString(1),r.getString(2),
                decode(r.getString(4),r.getInt(3))),args);
        Map<String,Stored> result=new HashMap<>();
        for(var row:rows) result.put(row.chunkId(),row);
        return result;
    }

    public void put(LiteratureStore.IndexedChunk chunk,String modelId,float[] vector) {
        jdbc.update("DELETE FROM literature_embedding WHERE chunk_id=? AND model_id=?",chunk.chunkId(),modelId);
        jdbc.update("INSERT INTO literature_embedding(chunk_id,model_id,chunk_sha256,dimension,vector_base64,created_at) VALUES (?,?,?,?,?,?)",
                chunk.chunkId(),modelId,chunk.chunkSha256(),vector.length,encode(vector),now());
    }

    private static String encode(float[] values) {
        ByteBuffer buffer=ByteBuffer.allocate(values.length*Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for(float value:values) buffer.putFloat(value);
        return Base64.getEncoder().encodeToString(buffer.array());
    }
    private static float[] decode(String encoded,int dimension) {
        if(dimension<1 || dimension>4096) throw new IllegalStateException("Invalid stored embedding dimension");
        byte[] bytes=Base64.getDecoder().decode(encoded);
        if(bytes.length!=dimension*Float.BYTES) throw new IllegalStateException("Invalid stored embedding length");
        ByteBuffer buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values=new float[dimension];
        for(int i=0;i<dimension;i++) values[i]=buffer.getFloat();
        return values;
    }
}
