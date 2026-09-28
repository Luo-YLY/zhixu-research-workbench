package local.research.workbench.literature;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class LiteratureEvidenceHighlightsTest {
    @Test void surfacesTheSpecificTrainingModulesInsteadOfTheReportHeadline() {
        String source="因子生成—策略构建—回测分析一体化工具。"+"训练背景。".repeat(80)
                +"核心训练内容可重点围绕选股空间设定、组合生成机制与策略回测框架三大模块展开。";
        assertThat(LiteratureEvidenceHighlights.from(source))
                .isEqualTo("核心训练内容可重点围绕选股空间设定、组合生成机制与策略回测框架三大模块展开。");
    }

    @Test void surfacesTheFullEnglishCandidateFeatureList() {
        String source="Model background. "+"Quality context. ".repeat(30)
                +"Inputs are grounded in prior research: features span profitability, accounting quality, "
                +"financial health, stability/risk, efficiency and management signalling.";
        assertThat(LiteratureEvidenceHighlights.from(source))
                .isEqualTo("Inputs are grounded in prior research: features span profitability, accounting quality, "
                        +"financial health, stability/risk, efficiency and management signalling.");
    }
}
