package dev.incident.workflow;

/**
 * The only seam between the workflow and an LLM. Production uses Spring AI + Bedrock; tests substitute a stub,
 * so orchestration is testable without credentials. Implementations must not expose tools to the model.
 */
public interface ModelClient {
    <T> ModelResult<T> call(String step, String systemPrompt, String userPrompt, Class<T> responseType);
}
