package local.research.workbench.assistant;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the private JSONL bridge without launching Codex, using credentials, or calling a model. */
class CodexGatewayProtocolTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COMPLETED = "{\"method\":\"turn/completed\",\"params\":{\"turn\":{\"status\":\"completed\"}}}\n";

    private static String message(String id, String text) {
        return JSON.writeValueAsString(Map.of("method", "item/completed", "params", Map.of(
                "item", Map.of("id", id, "type", "agentMessage", "text", text)))) + "\n";
    }

    private static CodexGateway.Connection connect(FakeProcess process) {
        return connect(process, () -> false, 5);
    }

    private static CodexGateway.Connection connect(FakeProcess process, BooleanSupplier cancelled, int timeout) {
        return new CodexGateway.Connection(process, cancelled, timeout, JSON);
    }

    @Test void writesNewlineDelimitedRequestsAndPreservesChineseText() throws Exception {
        var process = new FakeProcess("{\"id\":1,\"result\":{\"ready\":true}}\n");
        try (var connection = connect(process)) {
            connection.request(1, "initialize", Map.of("context", "中文上下文\n第二行"));
            connection.notify("initialized", Map.of());
            assertThat(connection.awaitResult(1).path("ready").asBoolean()).isTrue();
            var lines = process.sent().lines().toList();
            assertThat(lines).hasSize(2);
            var first = JSON.readTree(lines.getFirst());
            assertThat(first.path("id").asInt()).isEqualTo(1);
            assertThat(first.path("method").asText()).isEqualTo("initialize");
            assertThat(first.path("params").path("context").asText()).isEqualTo("中文上下文\n第二行");
            assertThat(first.has("jsonrpc")).isFalse();
            assertThat(JSON.readTree(lines.getLast()).has("id")).isFalse();
        }
        assertThat(process.destroyed).isTrue();
    }

    @Test void handlesOutOfOrderResponsesAndInterleavedCompletedMessages() throws Exception {
        var process = new FakeProcess("{\"id\":2,\"result\":{\"thread\":{\"id\":\"thread-a\"}}}\n"
                + message("answer-a", "研究问题")
                + "{\"id\":1,\"result\":{\"ready\":true}}\n"
                + message("answer-b", "建议先核实证据。") + COMPLETED);
        try (var connection = connect(process)) {
            assertThat(connection.awaitResult(1).path("ready").asBoolean()).isTrue();
            assertThat(connection.awaitResult(2).path("thread").path("id").asText()).isEqualTo("thread-a");
            assertThat(connection.answer()).isEqualTo("研究问题\n\n建议先核实证据。");
        }
    }

    @Test void rejectsServerToolAndPermissionRequestsWithoutSendingApproval() throws Exception {
        for (String method : new String[]{"item/tool/call", "item/commandExecution/requestApproval", "item/permissions/requestApproval"}) {
            var process = new FakeProcess(JSON.writeValueAsString(Map.of("id", 99, "method", method, "params", Map.of())) + "\n");
            try (var connection = connect(process)) {
                assertThatThrownBy(connection::answer).isInstanceOf(IllegalStateException.class).hasMessageContaining("未开放的工具或权限");
                assertThat(process.sent()).isEmpty();
            }
            assertThat(process.destroyed).isTrue();
        }
    }

    @Test void rejectsExecutionFileAndMcpItemsEvenWithoutApprovalRequest() throws Exception {
        for (String type : new String[]{"commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall"}) {
            var item = JSON.writeValueAsString(Map.of("method", "item/started", "params", Map.of("item", Map.of("id", "tool-1", "type", type)))) + "\n";
            var process = new FakeProcess(item + COMPLETED);
            try (var connection = connect(process)) {
                assertThatThrownBy(connection::answer).isInstanceOf(IllegalStateException.class).hasMessageContaining("未开放的能力");
            }
            assertThat(process.destroyed).isTrue();
        }
    }

    @Test void cancellationWinsBeforeReadingAnyResult() throws Exception {
        var process = new FakeProcess(message("answer", "must not be accepted") + COMPLETED);
        try (var connection = connect(process, () -> true, 5)) {
            assertThatThrownBy(connection::answer).isInstanceOf(CancellationException.class);
        }
        assertThat(process.destroyed).isTrue();
    }

    @Test void expiredDeadlineFailsWithoutWaitingForChildOutput() throws Exception {
        var process = new FakeProcess(message("answer", "must not be accepted") + COMPLETED);
        try (var connection = connect(process, () -> false, 0)) {
            assertThatThrownBy(connection::answer).isInstanceOf(IllegalStateException.class).hasMessageContaining("超时");
        }
        assertThat(process.destroyed).isTrue();
    }

    @Test void rejectsSuccessfulTurnWithoutText() throws Exception {
        var process = new FakeProcess(COMPLETED);
        try (var connection = connect(process)) {
            assertThatThrownBy(connection::answer).isInstanceOf(IllegalStateException.class).hasMessageContaining("未返回文字");
        }
    }

    @Test void failedTurnDoesNotLeakProviderErrorMessage() throws Exception {
        var process = new FakeProcess("{\"method\":\"turn/completed\",\"params\":{\"turn\":{\"status\":\"failed\",\"error\":{\"message\":\"private-provider-token\",\"codexErrorInfo\":\"usageLimitExceeded\"}}}}\n");
        try (var connection = connect(process)) {
            assertThatThrownBy(connection::answer).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("failed").hasMessageContaining("usageLimitExceeded")
                    .hasMessageNotContaining("private-provider-token");
        }
    }

    @Test void rpcErrorReportsCodeWithoutRawDetails() throws Exception {
        var process = new FakeProcess("{\"id\":1,\"error\":{\"code\":-32603,\"message\":\"private-provider-token\"}}\n");
        try (var connection = connect(process)) {
            assertThatThrownBy(() -> connection.awaitResult(1)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("-32603").hasMessageNotContaining("private-provider-token");
        }
    }

    @Test void enforcesAnswerAndProtocolLineSizeLimits() throws Exception {
        var answerProcess = new FakeProcess(message("answer", "字".repeat(24001)) + COMPLETED);
        try (var connection = connect(answerProcess)) {
            assertThatThrownBy(connection::answer).isInstanceOf(IllegalStateException.class).hasMessageContaining("24000");
        }
        var lineProcess = new FakeProcess("x".repeat(262146) + "\n");
        try (var connection = connect(lineProcess)) {
            assertThatThrownBy(connection::answer).isInstanceOf(IllegalStateException.class).hasMessageContaining("限制");
        }
    }

    @Test void malformedProtocolAndPrematureEofFailClearly() throws Exception {
        try (var connection = connect(new FakeProcess("not JSON\n"))) {
            assertThatThrownBy(connection::answer).isInstanceOf(IllegalStateException.class).hasMessageContaining("协议数据");
        }
        try (var connection = connect(new FakeProcess(""))) {
            assertThatThrownBy(connection::answer).isInstanceOf(IllegalStateException.class).hasMessageContaining("进程已退出");
        }
    }

    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        volatile boolean destroyed;
        FakeProcess(String output) { stdout = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)); }
        String sent() { return stdin.toString(StandardCharsets.UTF_8); }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public boolean isAlive() { return !destroyed; }
        @Override public void destroy() { destroyed = true; }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }
}
