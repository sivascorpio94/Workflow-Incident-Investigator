package dev.incident.workflow;

/** A typed model answer plus token metadata when the provider exposes it (otherwise null). */
public record ModelResult<T>(T value, Integer promptTokens, Integer completionTokens) {
    public static <T> ModelResult<T> of(T value) {
        return new ModelResult<>(value, null, null);
    }
}
