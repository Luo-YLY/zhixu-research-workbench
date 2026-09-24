package local.research.workbench.assistant;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Loopback-only HTTP contract tests. These never contact a model or use a real API credential. */
class ModelApiGatewayTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TEST_KEY = "local-test-secret-never-publish";
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();

    private ModelApiGateway gateway(HttpHandler handler, String key) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        executor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon(true).name("model-api-test-", 0).factory());
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            try { handler.handle(exchange); } finally { exchange.close(); }
        });
        server.start();
        return new ModelApiGateway(baseUrl(), "local-test-model", key, 5);
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

    private static String completed(String text) {
        return JSON.writeValueAsString(Map.of("choices", new Object[]{Map.of("finish_reason", "stop", "message",
                Map.of("role", "assistant", "content", text))}));
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @AfterEach void closeServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test void sendsConfiguredModelAndTextMessagesWithoutAnyTools() throws Exception {
        var observedBody = new AtomicReference<JsonNode>();
        var observedAuth = new AtomicReference<String>();
        var observedMethod = new AtomicReference<String>();
        var api = gateway(exchange -> {
            observedMethod.set(exchange.getRequestMethod());
            observedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            observedBody.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, completed("根据上下文，建议先核实研究假设。"));
        }, TEST_KEY);

        String answer = api.answer("中文研究问题\n页面快照", () -> false);

        assertThat(answer).isEqualTo("根据上下文，建议先核实研究假设。");
        assertThat(requests.get()).isEqualTo(1);
        assertThat(observedMethod.get()).isEqualTo("POST");
        assertThat(observedAuth.get()).isEqualTo("Bearer " + TEST_KEY);
        var body = observedBody.get();
        assertThat(body.path("model").asText()).isEqualTo("local-test-model");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("messages").size()).isEqualTo(2);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").path(1).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").path(1).path("content").asText()).isEqualTo("中文研究问题\n页面快照");
        for (String field : new String[]{"tools", "tool_choice", "functions", "function_call"}) {
            assertThat(body.has(field)).as("request must omit %s", field).isFalse();
        }
        assertThat(body.toString()).doesNotContain(TEST_KEY);
    }

    @Test void statusNeverSendsHttpOrExposesTheApiCredential() throws Exception {
        var api = gateway(exchange -> respond(exchange, 200, completed("unused")), TEST_KEY);

        var status = api.status();

        assertThat(status.available()).isTrue();
        assertThat(status.provider()).isEqualTo("MODEL_API");
        assertThat(status.message()).contains("实际连通性需发送后确认");
        assertThat(JSON.writeValueAsString(status)).doesNotContain(TEST_KEY, baseUrl());
        assertThat(requests.get()).isZero();
    }

    @Test void unauthenticatedLocalServiceDoesNotReceiveAnEmptyBearerHeader() throws Exception {
        var observedAuth = new AtomicReference<String>();
        var api = gateway(exchange -> {
            observedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, completed("本地回答"));
        }, "");

        assertThat(api.answer("问题", () -> false)).isEqualTo("本地回答");
        assertThat(observedAuth.get()).isNull();
    }

    @Test void unauthorizedResponseReportsOnlyHttpCodeAndNoProviderBody() throws Exception {
        var api = gateway(exchange -> respond(exchange, 401,
                "{\"error\":{\"message\":\"provider-private-detail " + TEST_KEY + "\"}}"), TEST_KEY);

        assertThatThrownBy(() -> api.answer("问题", () -> false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("provider-private-detail")
                .hasMessageNotContaining(TEST_KEY);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test void redirectIsNotFollowedAndCredentialNeverReachesRedirectTarget() throws Exception {
        var redirectRequests = new AtomicInteger();
        var api = gateway(exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected");
            respond(exchange, 307, "redirect-private-body");
        }, TEST_KEY);
        server.createContext("/redirected", exchange -> {
            redirectRequests.incrementAndGet();
            try { respond(exchange, 200, completed("must not be reached")); } finally { exchange.close(); }
        });

        assertThatThrownBy(() -> api.answer("问题", () -> false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 307").hasMessageNotContaining("redirect-private-body");
        assertThat(requests.get()).isEqualTo(1);
        assertThat(redirectRequests.get()).isZero();
    }

    @Test void refusesToolCallsEvenWhenResponseAlsoContainsText() throws Exception {
        String response = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"尝试执行工具",
                "tool_calls":[{"id":"call-1","type":"function","function":{"name":"write_file","arguments":"{}"}}]}}]}
                """;
        var api = gateway(exchange -> respond(exchange, 200, response), TEST_KEY);

        assertThatThrownBy(() -> api.answer("问题", () -> false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具调用");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test void alreadyCancelledRequestNeverLeavesTheProcess() throws Exception {
        var api = gateway(exchange -> respond(exchange, 200, completed("must not be called")), TEST_KEY);

        assertThatThrownBy(() -> api.answer("问题", () -> true)).isInstanceOf(CancellationException.class);
        assertThat(requests.get()).isZero();
    }

    @Test void missingOrUnsafeConfigurationIsUnavailableWithoutNetwork() {
        for (var pair : new String[][]{
                {"", ""}, {"", "model"}, {"http://127.0.0.1:9/v1", ""},
                {"http://remote.invalid/v1", "model"},
                {"https://user:private-password@remote.invalid/v1", "model"},
                {"https://remote.invalid/v1?secret=private-query", "model"},
                {"https://remote.invalid/v1#fragment", "model"}
        }) {
            var api = new ModelApiGateway(pair[0], pair[1], TEST_KEY, 5);
            assertThat(api.status().available()).as("configuration %s", pair[0]).isFalse();
            assertThat(api.status().message()).doesNotContain(TEST_KEY, "private-password", "private-query");
            assertThatThrownBy(() -> api.answer("question", () -> false)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(new ModelApiGateway("http://127.0.0.1:9/v1", "model", "key\r\ninjected: value", 5)
                .status().available()).isFalse();
    }
}
