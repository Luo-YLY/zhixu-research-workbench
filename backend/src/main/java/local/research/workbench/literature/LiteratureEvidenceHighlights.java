package local.research.workbench.literature;

import java.util.Locale;

/** Repeats a short, exact source span near an enumerated fact before the full excerpt. */
final class LiteratureEvidenceHighlights {
    private static final String[] MARKERS={
            "三大模块", "features span", "具体包括", "分别为", "构建方法如下",
            "consist of", "comprises", "including", "包括"
    };

    private LiteratureEvidenceHighlights() {}

    static String from(String excerpt) {
        String lower=excerpt.toLowerCase(Locale.ROOT);
        for(String marker:MARKERS) {
            int at=lower.indexOf(marker);
            if(at<0) continue;
            int start=marker.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)
                    ?excerpt.lastIndexOf('。',at):excerpt.lastIndexOf('.',at);
            start=Math.max(0,start+1);
            int end=marker.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)
                    ?excerpt.indexOf('。',at):excerpt.indexOf('.',at);
            end=end<0?excerpt.length():end+1;
            if(end-start>500) { start=Math.max(start,at-250);end=Math.min(end,at+250); }
            return excerpt.substring(start,end).replaceAll("\\s+"," ").strip();
        }
        return "";
    }
}
