package local.research.workbench.literature;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import local.research.workbench.shared.ApiException;

/** Resolves a browser text selection back to the exact stored source bytes. */
public final class SourceSelection {
    private SourceSelection() {}

    public static String resolve(String content,String selected,int maxLength) {
        String requested=selected==null?"":selected.strip();
        if(requested.isBlank()||requested.length()>maxLength)
            throw new ApiException(400,"INVALID_QUOTE","请选择不超过 "+maxLength+" 字的原文片段");
        if(content.contains(requested)) return requested;
        String[] parts=requested.split("\\s+");
        StringBuilder expression=new StringBuilder();
        for(String part:parts) {
            if(expression.length()>0) expression.append("\\s+");
            expression.append(Pattern.quote(part));
        }
        Matcher match=Pattern.compile(expression.toString()).matcher(content);
        if(!match.find()||match.group().length()>maxLength)
            throw new ApiException(400,"QUOTE_NOT_IN_SOURCE","所选文字不属于这段原文，请重新选择");
        return match.group();
    }
}
