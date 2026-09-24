package local.research.workbench.literature;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import local.research.workbench.shared.ApiException;

@Component
public class LiteratureParser {
    public record Page(int number, String text) {}
    public record Parsed(String mediaType, List<Page> pages) {}
    private static final int MAX_PAGES = 300;
    private static final int MAX_TEXT_CHARS = 1_000_000;

    public Parsed parse(String fileName, byte[] bytes) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) return pdf(bytes);
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                if (text.length() > MAX_TEXT_CHARS) throw invalid("文本过长，最多一百万字符");
                return new Parsed(lower.endsWith(".md") ? "text/markdown" : "text/plain", List.of(new Page(1,text)));
            } catch (CharacterCodingException e) { throw invalid("文本文件须为 UTF-8 编码"); }
        }
        throw invalid("仅支持 PDF、UTF-8 TXT 和 Markdown 文件");
    }

    private Parsed pdf(byte[] bytes) {
        if (bytes.length < 5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))
            throw invalid("文件内容不是有效 PDF");
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw invalid("暂不支持加密 PDF");
            int count = document.getNumberOfPages();
            if (count < 1 || count > MAX_PAGES) throw invalid("PDF 须为 1 至 300 页");
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            var pages = new ArrayList<Page>(count);
            int total = 0;
            for (int page=1; page<=count; page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = stripper.getText(document);
                total += text.length();
                if (total > MAX_TEXT_CHARS) throw invalid("提取出的文本过长，最多一百万字符");
                pages.add(new Page(page,text));
            }
            return new Parsed("application/pdf",pages);
        } catch (IOException e) { throw invalid("PDF 无法解析，请检查文件是否损坏"); }
    }

    private ApiException invalid(String message) { return new ApiException(422,"LITERATURE_PARSE_ERROR",message); }
}
