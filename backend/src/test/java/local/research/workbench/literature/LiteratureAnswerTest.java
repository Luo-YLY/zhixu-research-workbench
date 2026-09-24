package local.research.workbench.literature;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import local.research.workbench.assistant.AssistantGateway;
import local.research.workbench.audit.AuditLog;
import local.research.workbench.project.ProjectStore;
import local.research.workbench.shared.ApiException;
import org.junit.jupiter.api.Test;

class LiteratureAnswerTest {
    @Test void onlyKnownCitationsAreAcceptedAndEmptyRetrievalDoesNotCallModel() throws Exception {
        var parser=mock(LiteratureParser.class);
        var chunks=mock(LiteratureChunks.class);
        var store=mock(LiteratureStore.class);
        var projects=mock(ProjectStore.class);
        var gateway=mock(AssistantGateway.class);
        var embeddings=mock(LiteratureEmbeddings.class);
        when(projects.exists("project")).thenReturn(true);
        when(store.chunks("project",null)).thenReturn(List.of(new LiteratureStore.IndexedChunk(
                "chunk","document","Research paper","paper.pdf",2,1,"source-hash","chunk-hash","factor evidence")));
        when(gateway.status()).thenReturn(new AssistantGateway.Availability("TEST",true,"", ""));
        var service=new LiteratureService("target/test-answer-data",parser,chunks,store,
                mock(LiteratureEmbeddingStore.class),embeddings,new LiteratureSearch(),new LiteratureBilingual(gateway),
                projects,gateway,mock(AuditLog.class));
        when(gateway.answerJson(anyString(),any())).thenReturn(
                "{\"zh\":\"有证据 [C1]。\",\"en\":\"Evidence is present [C1].\"}",
                "{\"zh\":\"没有证据 [C2]。\",\"en\":\"Unsupported [C2].\"}");
        var answer=service.answer("project","factor evidence");
        assertThat(answer.status()).isEqualTo("GENERATED_UNVERIFIED");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.answerEn()).contains("[C1]");
        assertThat(answer.citations().getFirst().translation()).isNull();
        assertThat(answer.translationStatus()).isEqualTo("NOT_REQUESTED");
        verify(gateway).answerJson(argThat(prompt->prompt.contains("PDF 物理页 2") && prompt.contains("[C1]")),any());

        assertThatThrownBy(()->service.answer("project","factor evidence"))
                .isInstanceOf(ApiException.class).hasMessageContaining("不存在的引用");
        when(store.chunks("project",null)).thenReturn(List.of());
        assertThat(service.answer("project","unknown").status()).isEqualTo("NO_EVIDENCE");
        verify(gateway,times(2)).answerJson(anyString(),any());
    }
}
