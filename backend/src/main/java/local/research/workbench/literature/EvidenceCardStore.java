package local.research.workbench.literature;

import static local.research.workbench.shared.SqlSupport.time;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EvidenceCardStore {
    private static final String FIELDS="SELECT e.*,d.title,d.file_name FROM evidence_card e "
            +"JOIN literature_document d ON d.id=e.document_id ";
    private static final RowMapper<EvidenceCardApi.Card> CARD=(r,n)->{
        String documentId=r.getString("document_id");
        int page=r.getInt("page_number");
        String fileName=r.getString("file_name");
        String url="/api/literature/documents/"+documentId+"/file"
                +(fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")?"#page="+page:"");
        return new EvidenceCardApi.Card(r.getString("id"),r.getString("project_id"),documentId,
                r.getString("chunk_id"),r.getString("title"),fileName,page,
                r.getString("document_sha256"),r.getString("chunk_sha256"),r.getString("source_quote"),url,
                r.getString("research_claim"),r.getString("data_requirements"),r.getString("availability_note"),
                r.getString("reproduction_steps"),r.getString("observation"),r.getString("discrepancy"),
                r.getString("status"),time(r,"created_at"),time(r,"updated_at"),time(r,"reviewed_at"));
    };
    private final JdbcTemplate jdbc;
    public EvidenceCardStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<EvidenceCardApi.Card> list(String projectId) {
        return jdbc.query(FIELDS+"WHERE e.project_id=? ORDER BY e.created_at DESC,e.id",CARD,projectId);
    }
    public EvidenceCardApi.Card card(String projectId,String id) {
        var rows=jdbc.query(FIELDS+"WHERE e.project_id=? AND e.id=?",CARD,projectId,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void insert(EvidenceCardApi.Card card,OffsetDateTime timestamp) {
        jdbc.update("INSERT INTO evidence_card(id,project_id,document_id,chunk_id,document_sha256,chunk_sha256,"
                        +"page_number,source_quote,research_claim,data_requirements,availability_note,"
                        +"reproduction_steps,observation,discrepancy,status,created_at,updated_at) "
                        +"VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                card.id(),card.projectId(),card.documentId(),card.chunkId(),card.documentSha256(),
                card.chunkSha256(),card.pageNumber(),card.sourceQuote(),card.researchClaim(),
                card.dataRequirements(),card.availabilityNote(),card.reproductionSteps(),card.observation(),
                card.discrepancy(),"DRAFT",timestamp,timestamp);
    }
    public void update(String projectId,String id,EvidenceCardApi.Update request,OffsetDateTime timestamp) {
        jdbc.update("UPDATE evidence_card SET research_claim=?,data_requirements=?,availability_note=?,"
                        +"reproduction_steps=?,observation=?,discrepancy=?,status='DRAFT',reviewed_at=NULL,updated_at=? "
                        +"WHERE project_id=? AND id=?",
                request.researchClaim(),request.dataRequirements(),request.availabilityNote(),
                request.reproductionSteps(),request.observation(),request.discrepancy(),timestamp,projectId,id);
    }
    public void setReviewed(String projectId,String id,boolean reviewed,OffsetDateTime timestamp) {
        jdbc.update("UPDATE evidence_card SET status=?,reviewed_at=?,updated_at=? WHERE project_id=? AND id=?",
                reviewed?"REVIEWED":"DRAFT",reviewed?timestamp:null,timestamp,projectId,id);
    }
}
