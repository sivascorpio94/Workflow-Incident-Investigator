package dev.incident.domain;

import java.util.List;

public record AnalystReport(
        List<String> reviewedEvidenceIds,
        List<Finding> findings,
        List<String> openQuestions) {
    public AnalystReport {
        reviewedEvidenceIds = reviewedEvidenceIds == null ? List.of() : List.copyOf(reviewedEvidenceIds);
        findings = findings == null ? List.of() : List.copyOf(findings);
        openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
    }
}
