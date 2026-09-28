package local.research.workbench.literature;

import static org.assertj.core.api.Assertions.*;
import local.research.workbench.shared.ApiException;
import org.junit.jupiter.api.Test;

class SourceSelectionTest {
    @Test void resolvesBrowserWhitespaceToTheStoredSourceSpan() {
        assertThat(SourceSelection.resolve("选股空间设定、组合生成\n机制与策略回测框架。",
                "组合生成 机制与策略回测框架",100)).isEqualTo("组合生成\n机制与策略回测框架");
    }

    @Test void refusesAParaphraseEvenWhenItSharesSourceWords() {
        assertThatThrownBy(()->SourceSelection.resolve("选股空间设定、组合生成机制。","股票空间设置",100))
                .isInstanceOf(ApiException.class).hasMessageContaining("不属于这段原文");
    }
}
