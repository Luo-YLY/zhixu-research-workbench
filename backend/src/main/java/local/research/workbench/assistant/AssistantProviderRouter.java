package local.research.workbench.assistant;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Disabled is deliberate: installing the UI never opts a user into a paid model call. */
@Component
public class AssistantProviderRouter implements AssistantGateway {
    private final String provider;
    private final CodexGateway codex;
    private final ModelApiGateway modelApi;
    public AssistantProviderRouter(@Value("${workbench.assistant.provider:disabled}") String provider,
                                   CodexGateway codex,ModelApiGateway modelApi) {
        this.provider=provider.strip().toLowerCase(Locale.ROOT);this.codex=codex;this.modelApi=modelApi;
        if(!java.util.Set.of("disabled","codex","model-api").contains(this.provider))
            throw new IllegalArgumentException("workbench.assistant.provider must be disabled, codex or model-api");
    }
    @Override public Availability status() {
        return switch(provider) {
            case "codex" -> codex.status();
            case "model-api" -> modelApi.status();
            default -> new Availability("UNCONFIGURED",false,"","暂未连接模型，已预留模型 API 与 Codex CLI 适配。");
        };
    }
    @Override public String answer(String prompt,BooleanSupplier cancelled)throws Exception {
        return switch(provider) {
            case "codex" -> codex.answer(prompt,cancelled);
            case "model-api" -> modelApi.answer(prompt,cancelled);
            default -> throw new IllegalStateException("模型暂未连接；请先在服务端配置模型提供方。");
        };
    }
    @Override public String answerJson(String prompt,BooleanSupplier cancelled)throws Exception {
        return switch(provider) {
            case "codex" -> codex.answerJson(prompt,cancelled);
            case "model-api" -> modelApi.answerJson(prompt,cancelled);
            default -> throw new IllegalStateException("模型暂未连接；请先在服务端配置模型提供方。");
        };
    }
}
