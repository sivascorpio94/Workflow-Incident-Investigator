package dev.incident.domain;

import java.util.List;

public record IncidentAssessment(
        String incidentId,
        String summary,
        Severity severity,
        List<Finding> findings,
        List<Hypothesis> hypotheses,
        List<String> unknowns,
        List<Recommendation> recommendations,
        double confidence,
        boolean remediationExecuted) {
    public IncidentAssessment {
        findings = findings == null ? List.of() : List.copyOf(findings);
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        unknowns = unknowns == null ? List.of() : List.copyOf(unknowns);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
