package local.research.workbench.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class AssistantProviderRouterTest {
    @Test void disabledStatusAndAnswerNeverTouchEitherAdapter() {
        var codex=mock(CodexGateway.class);var api=mock(ModelApiGateway.class);
        var router=new AssistantProviderRouter("disabled",codex,api);
        var status=router.status();
        assertThat(status.provider()).isEqualTo("UNCONFIGURED");
        assertThat(status.available()).isFalse();
        assertThat(status.version()).isEmpty();
        assertThatThrownBy(()->router.answer("不得发送的请求",()->false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("暂未连接");
        verifyNoInteractions(codex,api);
    }
    @Test void invalidProviderFailsClosedWithoutAdapterAccess() {
        var codex=mock(CodexGateway.class);var api=mock(ModelApiGateway.class);
        assertThatThrownBy(()->new AssistantProviderRouter("accidental-value",codex,api)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(codex,api);
    }
}
