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
    @Test void translatesFiveHitsIndependently() throws Exception {
        var gateway=mock(AssistantGateway.class);
        when(gateway.status()).thenReturn(new AssistantGateway.Availability("TEST",true,"",""));
        when(gateway.answerJson(anyString(),any())).thenReturn(
                "{\"translation\":\"译文1\"}", "{\"translation\":\"译文2\"}",
                "{\"translation\":\"译文3\"}", "{\"translation\":\"译文4\"}",
                "{\"translation\":\"译文5\"}");
        var translated=new LiteratureBilingual(gateway).translate(hits());
        assertThat(translated.status()).isEqualTo("GENERATED_UNVERIFIED");
        assertThat(translated.hits()).extracting(LiteratureApi.Hit::translation)
                .containsExactly("译文1","译文2","译文3","译文4","译文5");
        verify(gateway,times(5)).answerJson(anyString(),any());
    }

    @Test void preservesOriginalWhenOneTranslationFails() throws Exception {
        var gateway=mock(AssistantGateway.class);
        when(gateway.status()).thenReturn(new AssistantGateway.Availability("TEST",true,"",""));
        when(gateway.answerJson(anyString(),any())).thenReturn(
                "{\"translation\":\"译文1\"}", "{\"translation\":\"译文2\"}",
                "{\"translation\":\"译文3\"}", "{\"translation\":\"译文4\"}",
                "{\"translation\":\"\"}");
        var translated=new LiteratureBilingual(gateway).translate(hits());
        assertThat(translated.status()).isEqualTo("PARTIAL");
        assertThat(translated.hits()).extracting(LiteratureApi.Hit::translation)
                .containsExactly("译文1","译文2","译文3","译文4",null);
        assertThat(translated.hits()).extracting(LiteratureApi.Hit::excerpt)
                .containsExactly("Evidence 1","Evidence 2","Evidence 3","Evidence 4","Evidence 5");
    }

    @Test void rejectsAbbreviatedTranslationOfLongEvidence() throws Exception {
        var gateway=mock(AssistantGateway.class);
        when(gateway.status()).thenReturn(new AssistantGateway.Availability("TEST",true,"",""));
        when(gateway.answerJson(anyString(),any())).thenReturn("{\"translation\":\"过短的摘要\"}");
        var longHit=new LiteratureApi.Hit("long","doc","Research","paper.pdf",1,1,"source","chunk",
                "Evidence ".repeat(120),1,"/api/literature/documents/doc/file#page=1",null,"en",null);
        var translated=new LiteratureBilingual(gateway).translate(List.of(longHit));
        assertThat(translated.status()).isEqualTo("FAILED");
        assertThat(translated.hits().getFirst().translation()).isNull();
    }

    @Test void plainTextFallbackRecoversEmptyJsonTranslation() throws Exception {
        var gateway=mock(AssistantGateway.class);
        when(gateway.status()).thenReturn(new AssistantGateway.Availability("TEST",true,"",""));
        when(gateway.answerJson(anyString(),any())).thenReturn("{}");
        when(gateway.answer(anyString(),any())).thenReturn("这是一段完整的译文。");
        var translated=new LiteratureBilingual(gateway).translate(List.of(hits().getFirst()));
        assertThat(translated.status()).isEqualTo("GENERATED_UNVERIFIED");
        assertThat(translated.hits().getFirst().translation()).isEqualTo("这是一段完整的译文。");
        verify(gateway).answer(anyString(),any());
    }

    private static List<LiteratureApi.Hit> hits() {
        return IntStream.rangeClosed(1,5).mapToObj(i->new LiteratureApi.Hit(
                "chunk-"+i,"doc","Research","paper.pdf",i,1,"source-hash","chunk-hash-"+i,
                "Evidence "+i,1,"/api/literature/documents/doc/file#page="+i,null,"en",null)).toList();
    }
}
