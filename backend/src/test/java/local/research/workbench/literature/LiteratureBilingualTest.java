package local.research.workbench.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.stream.IntStream;
import local.research.workbench.assistant.AssistantGateway;
import org.junit.jupiter.api.Test;

class LiteratureBilingualTest {
    @Test void translatesFiveHitsInBoundedBatches() throws Exception {
        var gateway=mock(AssistantGateway.class);
        when(gateway.status()).thenReturn(new AssistantGateway.Availability("TEST",true,"",""));
        when(gateway.answerJson(anyString(),any())).thenReturn(
                "{\"translations\":[\"译文1\",\"译文2\",\"译文3\"]}",
                "{\"translations\":[\"译文4\",\"译文5\"]}");
        var translated=new LiteratureBilingual(gateway).translate(hits());
        assertThat(translated.status()).isEqualTo("GENERATED_UNVERIFIED");
        assertThat(translated.hits()).extracting(LiteratureApi.Hit::translation)
                .containsExactly("译文1","译文2","译文3","译文4","译文5");
        verify(gateway,times(2)).answerJson(anyString(),any());
    }

    @Test void preservesOriginalsWhenOneBatchFails() throws Exception {
        var gateway=mock(AssistantGateway.class);
        when(gateway.status()).thenReturn(new AssistantGateway.Availability("TEST",true,"",""));
        when(gateway.answerJson(anyString(),any())).thenReturn(
                "{\"translations\":[\"译文1\",\"译文2\",\"译文3\"]}",
                "{\"translations\":[\"少一条\"]}",
                "{\"translations\":[\"译文4\"]}",
                "{\"translations\":[]}");
        var translated=new LiteratureBilingual(gateway).translate(hits());
        assertThat(translated.status()).isEqualTo("PARTIAL");
        assertThat(translated.hits()).extracting(LiteratureApi.Hit::translation)
                .containsExactly("译文1","译文2","译文3","译文4",null);
        assertThat(translated.hits()).extracting(LiteratureApi.Hit::excerpt)
                .containsExactly("Evidence 1","Evidence 2","Evidence 3","Evidence 4","Evidence 5");
    }

    private static List<LiteratureApi.Hit> hits() {
        return IntStream.rangeClosed(1,5).mapToObj(i->new LiteratureApi.Hit(
                "chunk-"+i,"doc","Research","paper.pdf",i,1,"source-hash","chunk-hash-"+i,
                "Evidence "+i,1,"/api/literature/documents/doc/file#page="+i,null,"en",null)).toList();
    }
}
