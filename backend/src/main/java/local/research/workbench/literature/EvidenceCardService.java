package local.research.workbench.literature;

import static local.research.workbench.shared.SqlSupport.id;
import static local.research.workbench.shared.SqlSupport.now;
import java.util.List;
import local.research.workbench.audit.AuditLog;
import local.research.workbench.project.ProjectStore;
import local.research.workbench.shared.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceCardService {
    private final EvidenceCardStore cards;
    private final LiteratureStore literature;
    private final ProjectStore projects;
    private final AuditLog audit;

    public EvidenceCardService(EvidenceCardStore cards,LiteratureStore literature,ProjectStore projects,AuditLog audit) {
        this.cards=cards;this.literature=literature;this.projects=projects;this.audit=audit;
    }

    public List<EvidenceCardApi.Card> list(String projectId) {
        requireProject(projectId);
        return cards.list(projectId);
    }

    @Transactional
    public EvidenceCardApi.Card create(EvidenceCardApi.Create request) {
        String projectId=request.projectId().toString();
        requireProject(projectId);
        var chunk=literature.chunk(projectId,request.chunkId().toString());
        if(chunk==null) throw ApiException.notFound("项目中的文献片段");
        String quote=SourceSelection.resolve(chunk.content(),request.sourceQuote(),1200);
        String claim=required(request.researchClaim(),500,"研究主张");
        var timestamp=now();
        var card=new EvidenceCardApi.Card(id(),projectId,chunk.documentId(),chunk.chunkId(),chunk.title(),
                chunk.fileName(),chunk.pageNumber(),chunk.documentSha256(),chunk.chunkSha256(),quote,
                LiteratureSearch.hit(chunk,0).sourceUrl(),claim,
                optional(request.dataRequirements(),2000,"所需数据"),
                optional(request.availabilityNote(),1000,"数据可用时间"),
                optional(request.reproductionSteps(),4000,"复现步骤"),
                optional(request.observation(),4000,"复现结果"),
                optional(request.discrepancy(),4000,"结果差异"),"DRAFT",
                timestamp.toInstant(),timestamp.toInstant(),null);
        cards.insert(card,timestamp);
        audit.record("EVIDENCE_CARD_CREATED",card.id(),"project="+projectId+"; chunk="+chunk.chunkId()
                +"; sha256="+chunk.chunkSha256());
        return card;
    }

    @Transactional
    public EvidenceCardApi.Card update(String id,EvidenceCardApi.Update request) {
        String projectId=request.projectId().toString();
        var before=card(projectId,id);
        var clean=new EvidenceCardApi.Update(request.projectId(),required(request.researchClaim(),500,"研究主张"),
                optional(request.dataRequirements(),2000,"所需数据"),
                optional(request.availabilityNote(),1000,"数据可用时间"),
                optional(request.reproductionSteps(),4000,"复现步骤"),
                optional(request.observation(),4000,"复现结果"),
                optional(request.discrepancy(),4000,"结果差异"));
        cards.update(projectId,id,clean,now());
        audit.record("EVIDENCE_CARD_UPDATED",id,"project="+projectId+"; source="+before.chunkSha256()
                +"; review reset to DRAFT");
        return card(projectId,id);
    }

    @Transactional
    public EvidenceCardApi.Card setReviewed(String id,String projectId,boolean reviewed) {
        card(projectId,id);
        cards.setReviewed(projectId,id,reviewed,now());
        audit.record(reviewed?"EVIDENCE_CARD_REVIEWED":"EVIDENCE_CARD_REOPENED",id,"project="+projectId);
        return card(projectId,id);
    }

    private EvidenceCardApi.Card card(String projectId,String id) {
        requireProject(projectId);
        var card=cards.card(projectId,id);
        if(card==null) throw ApiException.notFound("证据卡");
        return card;
    }
    private void requireProject(String id) {
        if(!projects.exists(id)) throw ApiException.notFound("项目");
    }
    private static String required(String value,int limit,String label) {
        String clean=value==null?"":value.strip();
        if(clean.isBlank()||clean.length()>limit)
            throw new ApiException(400,"INVALID_EVIDENCE_FIELD",label+"须为 1 至 "+limit+" 个字符");
        return clean;
    }
    private static String optional(String value,int limit,String label) {
        String clean=value==null?"":value.strip();
        if(clean.length()>limit) throw new ApiException(400,"INVALID_EVIDENCE_FIELD",label+"不能超过 "+limit+" 个字符");
        return clean;
    }
}
