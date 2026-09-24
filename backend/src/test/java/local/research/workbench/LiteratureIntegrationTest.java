package local.research.workbench;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import local.research.workbench.literature.LiteratureService;
import local.research.workbench.literature.LiteratureEmbeddingStore;
import local.research.workbench.literature.LiteratureStore;
import local.research.workbench.project.ProjectApi;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:literature-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workbench.worker.enabled=false","workbench.data-dir=target/test-literature-data"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class LiteratureIntegrationTest {
    @Autowired ProjectApi projects;
    @Autowired LiteratureService service;
    @Autowired LiteratureEmbeddingStore embeddings;
    @Autowired LiteratureStore literatureStore;
    @Autowired MockMvc mvc;

    @Test void importsSourcesWithPageCitationsAndChecksIntegrity() throws Exception {
        String projectId=projects.create(new ProjectApi.CreateProject("文献测试","")).id();
        byte[] pdf=twoPagePdf();
        mvc.perform(multipart("/api/literature/documents")
                        .file(new MockMultipartFile("file","paper.pdf","application/pdf",pdf))
                        .param("projectId",projectId).param("title","方法论文"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.chunkCount").value(2)).andReturn();
        String id=service.documents(projectId).getFirst().id();
        var hits=service.search(projectId,"secondpage evidence",5).hits();
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().pageNumber()).isEqualTo(2);
        assertThat(hits.getFirst().sourceUrl()).endsWith("/file#page=2");
        assertThat(service.file(id).bytes()).isEqualTo(pdf);
        mvc.perform(multipart("/api/literature/documents")
                        .file(new MockMultipartFile("file","paper-renamed.pdf","application/pdf",pdf))
                        .param("projectId",projectId).param("title","重复"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_DUPLICATE"));
        String other=projects.create(new ProjectApi.CreateProject("隔离测试","")).id();
        assertThat(service.search(other,"secondpage evidence",5).hits()).isEmpty();
        Path source=Path.of("target/test-literature-data/literature",id+".source");
        Files.writeString(source,"changed",StandardCharsets.UTF_8);
        assertThatThrownBy(()->service.file(id)).hasMessageContaining("哈希校验失败");
    }

    @Test void textSearchWorksWithoutModelAndRejectsEmptyPdf() throws Exception {
        String projectId=projects.create(new ProjectApi.CreateProject("文本测试","")).id();
        var file=new MockMultipartFile("file","notes.md","text/markdown","因子研究需要点时数据。\n回测不得使用未来信息。".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/literature/documents").file(file).param("projectId",projectId).param("title","研究笔记"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mediaType").value("text/markdown"));
        assertThat(service.search(projectId,"点时数据",5).hits()).hasSize(1);
        mvc.perform(post("/api/literature/answer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\""+projectId+"\",\"question\":\"点时数据是什么？\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));
        mvc.perform(multipart("/api/literature/documents")
                        .file(new MockMultipartFile("file","empty.pdf","application/pdf",new byte[]{1,2,3}))
                        .param("projectId",projectId).param("title","空文档"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test void selectedDocumentLimitsSearchAndAnswerToItsOwnProject() throws Exception {
        String projectId=projects.create(new ProjectApi.CreateProject("检索范围测试","")).id();
        String otherProject=projects.create(new ProjectApi.CreateProject("其他项目","")).id();
        for(var entry: new String[][]{{projectId,"alpha.txt","alpha evidence"},
                {projectId,"beta.txt","beta evidence"},{otherProject,"private.txt","private evidence"}}) {
            mvc.perform(multipart("/api/literature/documents")
                    .file(new MockMultipartFile("file",entry[1],"text/plain",entry[2].getBytes(StandardCharsets.UTF_8)))
                    .param("projectId",entry[0]).param("title",entry[1])).andExpect(status().isOk());
        }
        var docs=service.documents(projectId);
        String alpha=docs.stream().filter(d->d.fileName().equals("alpha.txt")).findFirst().orElseThrow().id();
        String privateId=service.documents(otherProject).getFirst().id();
        assertThat(service.search(projectId,"evidence",5).hits()).hasSize(2);
        mvc.perform(get("/api/literature/search").param("projectId",projectId).param("documentId",alpha)
                .param("q","evidence"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hits.length()").value(1))
                .andExpect(jsonPath("$.hits[0].documentId").value(alpha));
        mvc.perform(get("/api/literature/search").param("projectId",projectId).param("documentId",privateId)
                .param("q","evidence"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/literature/answer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\""+projectId+"\",\"documentId\":\""+alpha+"\",\"question\":\"beta\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_EVIDENCE"));
        mvc.perform(post("/api/literature/answer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\""+projectId+"\",\"documentId\":\""+privateId+"\",\"question\":\"private\"}"))
                .andExpect(status().isNotFound());
    }

    @Test void storesEmbeddingsInProjectScopedIndex() throws Exception {
        String projectId=projects.create(new ProjectApi.CreateProject("向量持久化测试","")).id();
        var file=new MockMultipartFile("file","vector.txt","text/plain","factor quality".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/literature/documents").file(file).param("projectId",projectId)
                .param("title","向量测试")).andExpect(status().isOk());
        var chunk=literatureStore.chunks(projectId,null).getFirst();
        embeddings.put(chunk,"test-model",new float[]{0.6f,0.8f});
        var loaded=embeddings.load(projectId,null,"test-model").get(chunk.chunkId());
        assertThat(loaded.chunkSha256()).isEqualTo(chunk.chunkSha256());
        assertThat(loaded.vector()).containsExactly(0.6f,0.8f);
        String other=projects.create(new ProjectApi.CreateProject("向量隔离测试","")).id();
        assertThat(embeddings.load(other,null,"test-model")).isEmpty();
    }

    private byte[] twoPagePdf() throws Exception {
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(String text:new String[]{"firstpage methods","secondpage evidence"}) {
                var page=new PDPage();pdf.addPage(page);
                try(var content=new PDPageContentStream(pdf,page)) {
                    content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    content.newLineAtOffset(60,700);content.showText(text);content.endText();
                }
            }
            pdf.save(out);return out.toByteArray();
        }
    }
}
