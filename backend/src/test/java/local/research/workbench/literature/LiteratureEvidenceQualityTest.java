package local.research.workbench.literature;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class LiteratureEvidenceQualityTest {
    @Test void narrativeCanOutrankAContentsPageAndNumericTable() {
        String question="Which training modules are used for strategy reproduction?";
        String contents="Figure 11 ................ 12\nFigure 12 ................ 13\nFigure 13 ................ 14";
        String table="2024 2.67% 3.06% 1.10% 4.35% 3.55% 5.03% 2025 4.67% 7.06%";
        String narrative="The training modules cover stock selection, portfolio construction and backtest analysis.";
        assertThat(0.628-LiteratureEvidenceQuality.penalty(question,"report.pdf",contents))
                .isLessThan(0.616-LiteratureEvidenceQuality.penalty(question,"report.pdf",narrative));
        assertThat(0.633-LiteratureEvidenceQuality.penalty(question,"report.pdf",table))
                .isLessThan(0.616-LiteratureEvidenceQuality.penalty(question,"report.pdf",narrative));
    }

    @Test void numericQuestionsRetainMoreOfTableScoreAndTextFilesAreNotPenalized() {
        String table="2024 2.67% 3.06% 1.10% 4.35% 3.55% 5.03% 2025 4.67% 7.06%";
        assertThat(LiteratureEvidenceQuality.penalty("What is the 2025 return?","report.pdf",table))
                .isEqualTo(0.015);
        assertThat(LiteratureEvidenceQuality.penalty("Which training modules?","notes.md",table))
                .isZero();
    }
}
