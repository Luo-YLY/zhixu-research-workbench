package local.research.workbench.assistant;

/** Model invocation is confined to this adapter; status performs local detection only. */
public interface AssistantGateway {
    record Availability(String provider, boolean available, String version, String message) {}
    Availability status();
    String answer(String prompt, java.util.function.BooleanSupplier cancelled) throws Exception;
    default String answerJson(String prompt, java.util.function.BooleanSupplier cancelled) throws Exception {
        return answer(prompt,cancelled);
    }
}
