package dev.incident.domain;

import java.util.List;

public record Hypothesis(
        CauseCategory category,
        String description,
        double confidence,
        List<String> supportingEvidenceIds,
        List<String> contradictingEvidenceIds) {
    public Hypothesis {
        supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : List.copyOf(supportingEvidenceIds);
        contradictingEvidenceIds = contradictingEvidenceIds == null ? List.of() : List.copyOf(contradictingEvidenceIds);
    }
}
