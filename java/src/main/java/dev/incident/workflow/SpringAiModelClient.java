package dev.incident.workflow;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "incident.model.provider", havingValue = "bedrock", matchIfMissing = true)
public class SpringAiModelClient implements ModelClient {

    private final ChatClient chatClient;

    public SpringAiModelClient(ChatClient.Builder builder) {
        // No tools, advisors or memory: the model can only read the prompt and return typed JSON.
        this.chatClient = builder.build();
    }

    @Override
    public <T> ModelResult<T> call(String step, String systemPrompt, String userPrompt, Class<T> type) {
        // Prompts are passed as template parameters so braces in evidence text are never interpreted as variables.
        var response = chatClient.prompt()
                .system(s -> s.text("{text}").param("text", systemPrompt))
                .user(u -> u.text("{text}").param("text", userPrompt))
                .call()
                .responseEntity(type);
        Usage usage = response.response() == null || response.response().getMetadata() == null
                ? null : response.response().getMetadata().getUsage();
        return new ModelResult<>(response.entity(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }
}
