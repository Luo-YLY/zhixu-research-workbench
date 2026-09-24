package local.research.workbench.literature;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Keeps each citation inside one source page. Offsets are deterministic for a fixed source and parser version. */
@Component
public class LiteratureChunks {
    public record Part(int page, int number, String text) {}
    private static final int WINDOW = 1100;
    private static final int OVERLAP = 160;

    public List<Part> split(List<LiteratureParser.Page> pages) {
        var result = new ArrayList<Part>();
        for (var page : pages) {
            String text = page.text().replace('\u0000',' ').replaceAll("[\\t\\r]+"," ")
                    .replaceAll("[ ]{2,}"," ").strip();
            int pos = 0;
            int number = 1;
            while (pos < text.length()) {
                int end = Math.min(pos + WINDOW,text.length());
                if (end < text.length()) {
                    int boundary = Math.max(text.lastIndexOf('\n',end),Math.max(text.lastIndexOf('。',end),text.lastIndexOf('.',end)));
                    if (boundary > pos + WINDOW/2) end = boundary + 1;
                }
                String content = text.substring(pos,end).strip();
                if (!content.isBlank()) result.add(new Part(page.number(),number++,content));
                if (end == text.length()) break;
                pos = Math.max(pos+1,end-OVERLAP);
            }
        }
        return result;
    }
}
