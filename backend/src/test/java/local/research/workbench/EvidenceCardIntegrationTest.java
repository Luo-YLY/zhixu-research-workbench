package local.research.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import local.research.workbench.literature.LiteratureStore;
import local.research.workbench.project.ProjectApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:evidence-card-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workbench.worker.enabled=false","workbench.data-dir=target/test-evidence-card-data"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class EvidenceCardIntegrationTest {
    @Autowired ProjectApi projects;
    @Autowired LiteratureStore literature;
    @Autowired MockMvc mvc;
    private final JsonMapper json=JsonMapper.builder().build();

    @Test void anchorsQuoteAndResetsReviewAfterEditing() throws Exception {
        String projectId=projectWithDocument();
        var chunk=literature.chunks(projectId,null).getFirst();
        String create=json.writeValueAsString(Map.of(
                "projectId",projectId,"chunkId",chunk.chunkId(),"sourceQuote","data availability time",
                "researchClaim","复现需要点时可用时间","dataRequirements","原始价格与成交量",
                "availabilityNote","交易日收盘后","reproductionSteps","对齐时间戳后计算",
                "observation","尚未复现","discrepancy","等待核对"));
        var created=mvc.perform(post("/api/evidence-cards").contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.sourceQuote").value("data availability time"))
                .andExpect(jsonPath("$.chunkSha256").value(chunk.chunkSha256()))
                .andReturn();
        String id=json.readTree(created.getResponse().getContentAsByteArray()).path("id").asText();
        mvc.perform(post("/api/evidence-cards/"+id+"/review").param("projectId",projectId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVIEWED"))
                .andExpect(jsonPath("$.reviewedAt").exists());
        String update=json.writeValueAsString(Map.of("projectId",projectId,"researchClaim","复现需要点时可用时间与原始文件",
                "dataRequirements","价格、成交量","availabilityNote","收盘后","reproductionSteps","对齐时间戳",
                "observation","复现完成","discrepancy","误差 0.1"));
        mvc.perform(put("/api/evidence-cards/"+id).contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                .andExpect(jsonPath("$.sourceQuote").value("data availability time"))
                .andExpect(jsonPath("$.observation").value("复现完成"));
        mvc.perform(get("/api/evidence-cards").param("projectId",projectId))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
    }

    @Test void rejectsInventedQuotesAndCrossProjectAccess() throws Exception {
        String projectId=projectWithDocument();
        String other=projects.create(new ProjectApi.CreateProject("另一项目","隔离检查")).id();
        var chunk=literature.chunks(projectId,null).getFirst();
        String foreign=json.writeValueAsString(Map.of("projectId",other,"chunkId",chunk.chunkId(),
                "sourceQuote","data availability time","researchClaim","不应保存"));
        mvc.perform(post("/api/evidence-cards").contentType(MediaType.APPLICATION_JSON).content(foreign))
                .andExpect(status().isNotFound());
        String invented=json.writeValueAsString(Map.of("projectId",projectId,"chunkId",chunk.chunkId(),
                "sourceQuote","This sentence was invented.","researchClaim","不应保存"));
        mvc.perform(post("/api/evidence-cards").contentType(MediaType.APPLICATION_JSON).content(invented))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUOTE_NOT_IN_SOURCE"));
        assertThat(literature.chunk(other,chunk.chunkId())).isNull();
    }

    @Test void translatesOnlyASelectedSourceSpanAndPreservesOriginalWhenModelIsOff() throws Exception {
        String projectId=projectWithDocument();
        var chunk=literature.chunks(projectId,null).getFirst();
        String request=json.writeValueAsString(Map.of("projectId",projectId,"chunkId",chunk.chunkId(),
                "sourceText","data availability time"));
        mvc.perform(post("/api/literature/translate").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNCONFIGURED"))
                .andExpect(jsonPath("$.sourceText").value("data availability time"))
                .andExpect(jsonPath("$.translation").doesNotExist());
    }

    private String projectWithDocument() throws Exception {
        String projectId=projects.create(new ProjectApi.CreateProject("证据卡测试","复现项目")).id();
        mvc.perform(multipart("/api/literature/documents")
                        .file(new MockMultipartFile("file","source.txt","text/plain",
                                "Keep the source file, SHA-256 and data availability time for reproduction."
                                        .getBytes(StandardCharsets.UTF_8)))
                        .param("projectId",projectId).param("title","点时数据来源"))
                .andExpect(status().isOk());
        return projectId;
    }
}
