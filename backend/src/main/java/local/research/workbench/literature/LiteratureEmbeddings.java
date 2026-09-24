package local.research.workbench.literature;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import local.research.workbench.artifact.ArtifactStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** OpenAI-compatible multilingual embedding adapter; disabled unless explicitly configured. */
@Component
public class LiteratureEmbeddings {
    private final JsonMapper json=JsonMapper.builder().build();
    private final String provider,baseUrl,model,apiKey,revision;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public LiteratureEmbeddings(@Value("${workbench.literature.embedding.provider:disabled}") String provider,
                                @Value("${workbench.literature.embedding.base-url:}") String baseUrl,
                                @Value("${workbench.literature.embedding.model:}") String model,
                                @Value("${workbench.literature.embedding.api-key:}") String apiKey,
                                @Value("${workbench.literature.embedding.revision:v1}") String revision) {
        this.provider=provider.strip().toLowerCase(Locale.ROOT);
        this.baseUrl=baseUrl.strip();this.model=model.strip();this.apiKey=apiKey;this.revision=revision.strip();
        if(!Set.of("disabled","openai").contains(this.provider)) throw new IllegalArgumentException("Invalid embedding provider");
        if(this.model.length()>180||this.revision.isBlank()||this.revision.length()>32)
            throw new IllegalArgumentException("Invalid embedding model or revision");
    }
    public boolean available() { return provider.equals("openai")&&!model.isBlank()&&!baseUrl.isBlank(); }
    public String modelId() {
        return model+"@"+revision+"-"+ArtifactStore.sha256(baseUrl.getBytes(StandardCharsets.UTF_8)).substring(0,12);
    }
    public String status() { return available()?"CONFIGURED":"UNCONFIGURED"; }

    public List<float[]> embed(List<String> texts) throws Exception {
        if(!available()) throw new IllegalStateException("Embedding model is not configured");
        if(texts.isEmpty() || texts.size()>16 || texts.stream().anyMatch(s->s.isBlank()||s.length()>4000))
            throw new IllegalArgumentException("Invalid embedding batch");
        URI endpoint=endpoint();
        var builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(180))
                .header("Content-Type","application/json").header("Accept","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("model",model,"input",texts)),StandardCharsets.UTF_8));
        if(!apiKey.isBlank()) {
            if(apiKey.contains("\n")||apiKey.contains("\r")) throw new IllegalArgumentException("Invalid embedding credential");
            builder.header("Authorization","Bearer "+apiKey);
        }
        var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()<200||response.statusCode()>=300)
            throw new IllegalStateException("Embedding service returned HTTP "+response.statusCode());
        if(response.body().length>8*1024*1024) throw new IllegalStateException("Embedding response too large");
        var data=json.readTree(response.body()).path("data");
        if(!data.isArray()||data.size()!=texts.size()) throw new IllegalStateException("Embedding response count mismatch");
        Map<Integer,float[]> indexed=new HashMap<>();int dimension=0;
        for(var item:data) {
            int index=item.path("index").asInt(-1);
            var values=item.path("embedding");
            if(index<0||index>=texts.size()||indexed.containsKey(index)||!values.isArray()||values.isEmpty()||values.size()>4096)
                throw new IllegalStateException("Invalid embedding response");
            if(dimension==0) dimension=values.size();
            if(values.size()!=dimension) throw new IllegalStateException("Embedding dimensions differ");
            float[] vector=new float[dimension];double norm=0;
            for(int i=0;i<dimension;i++) {
                double value=values.get(i).asDouble(Double.NaN);
                if(!Double.isFinite(value)) throw new IllegalStateException("Invalid embedding value");
                vector[i]=(float)value;norm+=value*value;
            }
            if(norm<1e-12) throw new IllegalStateException("Zero embedding vector");
            float scale=(float)Math.sqrt(norm);
            for(int i=0;i<dimension;i++) vector[i]/=scale;
            indexed.put(index,vector);
        }
        var ordered=new ArrayList<float[]>(texts.size());
        for(int i=0;i<texts.size();i++) ordered.add(indexed.get(i));
        if(ordered.stream().anyMatch(v->v==null)) throw new IllegalStateException("Missing embedding vector");
        return ordered;
    }
    private URI endpoint() {
        URI base=URI.create(baseUrl);
        String host=base.getHost()==null?"":base.getHost().toLowerCase(Locale.ROOT);
        boolean trustedLocal=Set.of("localhost","127.0.0.1","::1","model","embed","model-runner.docker.internal").contains(host);
        if(host.isBlank()||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||
                !("https".equals(base.getScheme())||("http".equals(base.getScheme())&&trustedLocal)))
            throw new IllegalArgumentException("Invalid embedding endpoint");
        return URI.create(baseUrl.replaceAll("/+$","")+"/embeddings");
    }
}
