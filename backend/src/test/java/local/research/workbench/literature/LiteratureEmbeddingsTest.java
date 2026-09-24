package local.research.workbench.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LiteratureEmbeddingsTest {
    private HttpServer server;
    @AfterEach void stop() { if(server!=null) server.stop(0); }

    @Test void acceptsOutOfOrderOpenAiEmbeddingsAndNormalizesThem() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings",exchange->{
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(request).contains("first","second","bge-m3");
            byte[] response="{\"data\":[{\"index\":1,\"embedding\":[0,3]},{\"index\":0,\"embedding\":[4,0]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);
            try(var output=exchange.getResponseBody()) { output.write(response); }
        });
        server.setExecutor(Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).factory()));
        server.start();
        var adapter=new LiteratureEmbeddings("openai","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","bge-m3","","v1");
        var values=adapter.embed(List.of("first","second"));
        assertThat(values.get(0)).containsExactly(1f,0f);
        assertThat(values.get(1)).containsExactly(0f,1f);
        assertThat(adapter.modelId()).startsWith("bge-m3@");
    }

    @Test void rejectsUntrustedPlainHttpEndpoint() {
        var adapter=new LiteratureEmbeddings("openai","http://example.com/v1","bge-m3","","v1");
        assertThatThrownBy(()->adapter.embed(List.of("query"))).isInstanceOf(IllegalArgumentException.class);
    }
}
