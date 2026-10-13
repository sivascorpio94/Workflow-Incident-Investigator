package dev.incident.observability;

import java.util.List;

public record InvestigationTrace(String investigationId, List<StepTrace> steps) {
    public record StepTrace(
            String name,
            long durationMs,
            Outcome outcome,
            String error,
            Integer promptTokens,
            Integer completionTokens) {}

    public enum Outcome { OK, FAILED }
}
