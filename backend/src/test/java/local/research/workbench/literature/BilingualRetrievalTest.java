package local.research.workbench.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
                new LiteratureBilingual(gateway),projects,gateway,mock(AuditLog.class));

        var en=service.search("project","doc-en","因子质量",5);
        assertThat(en.hits()).extracting(LiteratureApi.Hit::chunkId).containsExactly("en");
        assertThat(en.retrievalVersion()).isEqualTo("hybrid-bm25-embedding-v1");
        assertThat(en.semanticStatus()).isEqualTo("READY");
        assertThat(en.hits().getFirst().excerpt()).isEqualTo(english.content());
        var zh=service.search("project","doc-zh","factor quality",5);
        assertThat(zh.hits()).extracting(LiteratureApi.Hit::chunkId).containsExactly("zh");
        assertThat(zh.hits().getFirst().documentId()).isEqualTo("doc-zh");
        verify(embeddings).embed(eq(List.of("因子质量")));
        verify(embeddings).embed(eq(List.of("factor quality")));
    }
}
