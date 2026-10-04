package dev.incident.domain;

import java.util.List;

/** Alert facts only - triage must not diagnose a cause. */
public record TriageResult(
        String alertSummary,
        String affectedSystem,
        List<String> observedSymptoms,
        List<String> alertEvidenceIds) {
    public TriageResult {
        observedSymptoms = observedSymptoms == null ? List.of() : List.copyOf(observedSymptoms);
        alertEvidenceIds = alertEvidenceIds == null ? List.of() : List.copyOf(alertEvidenceIds);
    }
}
