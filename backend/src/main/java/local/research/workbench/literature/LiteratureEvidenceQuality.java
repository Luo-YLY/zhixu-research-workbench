package local.research.workbench.literature;

import java.util.Locale;
import java.util.regex.Pattern;

/** Small ranking adjustment for PDF extraction artifacts, applied only to indexed candidates. */
final class LiteratureEvidenceQuality {
    private static final Pattern DOT_LEADER=Pattern.compile("\\.{4,}");
    private static final Pattern NUMERIC_QUESTION=Pattern.compile(
            "\\d|percent|percentage|return|ratio|performance|收益|比例|数值|多少|回报",
            Pattern.CASE_INSENSITIVE);

    private LiteratureEvidenceQuality() {}

    static double penalty(String query,String fileName,String excerpt) {
        if(!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")||excerpt.isBlank()) return 0;
        double penalty=0;
        if(DOT_LEADER.matcher(excerpt).results().limit(3).count()>=3) penalty+=0.05;
        long digits=excerpt.chars().filter(Character::isDigit).count();
        if((double)digits/excerpt.length()>=0.18) penalty+=0.03;
        return NUMERIC_QUESTION.matcher(query).find()?penalty/2:penalty;
    }
}
