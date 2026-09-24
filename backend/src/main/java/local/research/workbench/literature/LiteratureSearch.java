package local.research.workbench.literature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Small, deterministic lexical baseline. A versioned score makes retrieval regression measurable. */
@Component
public class LiteratureSearch {
    public static final String VERSION = "lexical-bm25-v1";
    private static final Pattern WORDS=Pattern.compile("[A-Za-z0-9]+|[\\p{IsHan}]+");

    public List<LiteratureApi.Hit> rank(String query,List<LiteratureStore.IndexedChunk> chunks,int limit) {
        var terms=new HashSet<>(tokens(query));
        if(terms.isEmpty() || chunks.isEmpty()) return List.of();
        var indexed=new ArrayList<Map<String,Integer>>(chunks.size());
        var df=new HashMap<String,Integer>();
        int lengthSum=0;
        for(var chunk:chunks) {
            var frequencies=new HashMap<String,Integer>();
            for(String term:tokens(chunk.content())) frequencies.merge(term,1,Integer::sum);
            indexed.add(frequencies);
            lengthSum+=frequencies.values().stream().mapToInt(Integer::intValue).sum();
            for(String term:terms) if(frequencies.containsKey(term)) df.merge(term,1,Integer::sum);
        }
        double average=Math.max(1.0,(double)lengthSum/chunks.size());
        var hits=new ArrayList<LiteratureApi.Hit>();
        for(int i=0;i<chunks.size();i++) {
            var chunk=chunks.get(i);
            var frequency=indexed.get(i);
            int length=frequency.values().stream().mapToInt(Integer::intValue).sum();
            double score=0;
            for(String term:terms) {
                int tf=frequency.getOrDefault(term,0);
                if(tf==0)continue;
                int found=df.getOrDefault(term,0);
                double idf=Math.log(1+(chunks.size()-found+0.5)/(found+0.5));
                score+=idf*(tf*2.2)/(tf+1.2*(0.25+0.75*length/average));
            }
            if(score>0) hits.add(new LiteratureApi.Hit(chunk.chunkId(),chunk.documentId(),chunk.title(),chunk.fileName(),
                    chunk.pageNumber(),chunk.chunkNumber(),chunk.documentSha256(),chunk.chunkSha256(),
                    chunk.content(),Math.round(score*10000.0)/10000.0,
                    "/api/literature/documents/"+chunk.documentId()+"/file"+
                            (chunk.fileName().toLowerCase(Locale.ROOT).endsWith(".pdf")?"#page="+chunk.pageNumber():"")));
        }
        hits.sort(Comparator.comparingDouble(LiteratureApi.Hit::score).reversed()
                .thenComparing(LiteratureApi.Hit::documentId).thenComparingInt(LiteratureApi.Hit::pageNumber)
                .thenComparingInt(LiteratureApi.Hit::chunkNumber));
        return hits.subList(0,Math.min(limit,hits.size()));
    }

    static List<String> tokens(String text) {
        var tokens=new ArrayList<String>();
        var matcher=WORDS.matcher(text.toLowerCase(Locale.ROOT));
        while(matcher.find()) {
            String word=matcher.group();
            if(Character.UnicodeScript.of(word.codePointAt(0))==Character.UnicodeScript.HAN) {
                if(word.length()==1) tokens.add(word);
                else for(int i=0;i<word.length()-1;i++) tokens.add(word.substring(i,i+2));
            } else if(word.length()>1) tokens.add(word);
        }
        return tokens;
    }
}
