package local.research.workbench.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.Map;
import local.research.workbench.assistant.AssistantGateway;
import local.research.workbench.audit.AuditLog;
import local.research.workbench.project.ProjectStore;
import org.junit.jupiter.api.Test;

class BilingualRetrievalTest {
    @Test void retrievesEnglishFromChineseAndChineseFromEnglishWithinSelectedDocument() throws Exception {
        var store=mock(LiteratureStore.class);
        var vectors=mock(LiteratureEmbeddingStore.class);
        var embeddings=mock(LiteratureEmbeddings.class);
        var projects=mock(ProjectStore.class);
        var gateway=mock(AssistantGateway.class);
        var english=new LiteratureStore.IndexedChunk("en","doc-en","English paper","paper.pdf",3,1,
                "source-en","sha-en","Machine learning improves factor quality.");
        var chinese=new LiteratureStore.IndexedChunk("zh","doc-zh","中文研究","note.md",1,1,
                "source-zh","sha-zh","机器学习改进因子质量。");
        when(projects.exists("project")).thenReturn(true);
        when(store.document("doc-en")).thenReturn(new LiteratureApi.Document("doc-en","project","English paper",
                "paper.pdf","application/pdf","source-en",10,3,1,java.time.Instant.now()));
        when(store.document("doc-zh")).thenReturn(new LiteratureApi.Document("doc-zh","project","中文研究",
                "note.md","text/markdown","source-zh",10,1,1,java.time.Instant.now()));
        when(store.chunks("project","doc-en")).thenReturn(List.of(english));
        when(store.chunks("project","doc-zh")).thenReturn(List.of(chinese));
        when(vectors.load("project","doc-en","bge-m3")).thenReturn(Map.of("en",
                new LiteratureEmbeddingStore.Stored("en","sha-en",new float[]{1,0})));
        when(vectors.load("project","doc-zh","bge-m3")).thenReturn(Map.of("zh",
                new LiteratureEmbeddingStore.Stored("zh","sha-zh",new float[]{1,0})));
        when(embeddings.available()).thenReturn(true);
        when(embeddings.modelId()).thenReturn("bge-m3");
        when(embeddings.embed(any())).thenReturn(List.of(new float[]{1,0}));
        when(gateway.status()).thenReturn(new AssistantGateway.Availability("UNCONFIGURED",false,"",""));
        var service=new LiteratureService("target/test-bilingual-data",mock(LiteratureParser.class),
                mock(LiteratureChunks.class),store,vectors,embeddings,new LiteratureSearch(),
                new LiteratureBilingual(gateway),projects,gateway,mock(AuditLog.class),0.45,0.55);

        var en=service.search("project","doc-en","因子质量",5);
        assertThat(en.hits()).extracting(LiteratureApi.Hit::chunkId).containsExactly("en");
        assertThat(en.retrievalVersion()).isEqualTo("hybrid-bm25-embedding-v2");
        assertThat(en.semanticStatus()).isEqualTo("READY");
        assertThat(en.hits().getFirst().excerpt()).isEqualTo(english.content());
        var zh=service.search("project","doc-zh","factor quality",5);
        assertThat(zh.hits()).extracting(LiteratureApi.Hit::chunkId).containsExactly("zh");
        assertThat(zh.hits().getFirst().documentId()).isEqualTo("doc-zh");
        verify(embeddings).embed(eq(List.of("因子质量")));
        verify(embeddings).embed(eq(List.of("factor quality")));
    }

    @Test void crossLanguageEvidenceBeatsKeywordRichDisclaimerInProjectSearch() throws Exception {
        var store=mock(LiteratureStore.class);
        var vectors=mock(LiteratureEmbeddingStore.class);
        var embeddings=mock(LiteratureEmbeddings.class);
        var projects=mock(ProjectStore.class);
        var gateway=mock(AssistantGateway.class);
        String question="When reproducing a research report, which three records must be retained?";
        var evidence=new LiteratureStore.IndexedChunk("evidence","note","Research records","note.md",1,1,
                "source-evidence","sha-evidence","研报复现必须保留来源文件、SHA-256 哈希和数据可用时间。");
        var disclaimer=new LiteratureStore.IndexedChunk("disclaimer","report","Report disclaimer","report.pdf",31,1,
                "source-report","sha-disclaimer","Research report records must be retained under this all-weather global research disclaimer.");
        when(projects.exists("project")).thenReturn(true);
        when(store.chunks("project",null)).thenReturn(List.of(evidence,disclaimer));
        when(vectors.load("project",null,"bge-m3")).thenReturn(Map.of(
                "evidence",new LiteratureEmbeddingStore.Stored("evidence","sha-evidence",
                        new float[]{0.54f,(float)Math.sqrt(1-0.54*0.54)}),
                "disclaimer",new LiteratureEmbeddingStore.Stored("disclaimer","sha-disclaimer",
                        new float[]{0.47f,(float)Math.sqrt(1-0.47*0.47)})));
        when(embeddings.available()).thenReturn(true);
        when(embeddings.modelId()).thenReturn("bge-m3");
        when(embeddings.embed(any())).thenReturn(List.of(new float[]{1,0}));
        when(gateway.status()).thenReturn(new AssistantGateway.Availability("UNCONFIGURED",false,"",""));
        var service=new LiteratureService("target/test-bilingual-data",mock(LiteratureParser.class),
                mock(LiteratureChunks.class),store,vectors,embeddings,new LiteratureSearch(),
                new LiteratureBilingual(gateway),projects,gateway,mock(AuditLog.class),0.45,0.55);

        var result=service.search("project",null,question,5,false);
        assertThat(result.hits()).extracting(LiteratureApi.Hit::chunkId).containsExactly("evidence");
        assertThat(result.semanticStatus()).isEqualTo("READY");

        String unrelated="What is the weather in Paris tomorrow?";
        when(embeddings.embed(eq(List.of(unrelated)))).thenReturn(List.of(new float[]{-1,0}));
        assertThat(service.search("project",null,unrelated,5,false).hits()).isEmpty();
    }

    @Test void weakSemanticCandidatesAreShownWithoutGeneratingAnAnswer() throws Exception {
        var store=mock(LiteratureStore.class);
        var vectors=mock(LiteratureEmbeddingStore.class);
        var embeddings=mock(LiteratureEmbeddings.class);
        var projects=mock(ProjectStore.class);
        var gateway=mock(AssistantGateway.class);
        var chunk=new LiteratureStore.IndexedChunk("table","report","Factor report","report.pdf",19,1,
                "source","sha","Annual factor performance table.");
        when(projects.exists("project")).thenReturn(true);
        when(store.chunks("project",null)).thenReturn(List.of(chunk));
        when(vectors.load("project",null,"bge-m3")).thenReturn(Map.of("table",
                new LiteratureEmbeddingStore.Stored("table","sha",new float[]{0.50f,0.8660254f})));
        when(embeddings.available()).thenReturn(true);
        when(embeddings.modelId()).thenReturn("bge-m3");
        when(embeddings.embed(any())).thenReturn(List.of(new float[]{1,0}));
        when(gateway.status()).thenReturn(new AssistantGateway.Availability("TEST",true,"",""));
        var service=new LiteratureService("target/test-bilingual-data",mock(LiteratureParser.class),
                mock(LiteratureChunks.class),store,vectors,embeddings,new LiteratureSearch(),
                new LiteratureBilingual(gateway),projects,gateway,mock(AuditLog.class),0.45,0.55);

        var answer=service.answer("project","Tesla vehicle deliveries in 2025 Q3");
        assertThat(answer.status()).isEqualTo("NO_EVIDENCE");
        assertThat(answer.citations()).hasSize(1);
        verify(gateway,never()).answerJson(any(),any());
    }
}
