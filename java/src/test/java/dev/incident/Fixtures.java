package dev.incident;

import dev.incident.domain.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class Fixtures {
    private Fixtures() {}

    static final Instant START = Instant.parse("2026-03-12T09:30:00Z");
    static final Instant END = Instant.parse("2026-03-12T10:30:00Z");

    static EvidenceItem ev(String id, EvidenceType t, String ts) {
        return new EvidenceItem(id, t, "src", Instant.parse(ts), "content of " + id);
    }

    static InvestigationRequest request() {
        return new InvestigationRequest("INC-1", "backlog", new TimeWindow(START, END), List.of(
                ev("E-ALERT-1", EvidenceType.ALERT, "2026-03-12T10:02:00Z"),
                ev("E-MET-1", EvidenceType.METRIC, "2026-03-12T10:00:00Z"),
                ev("E-LOG-1", EvidenceType.LOG, "2026-03-12T09:41:00Z"),
                ev("E-DEP-1", EvidenceType.DEPLOYMENT, "2026-03-12T10:05:00Z"),
                ev("E-RB-1", EvidenceType.RUNBOOK, "2026-03-01T00:00:00Z")));
    }

    static InvestigationRequest with(List<EvidenceItem> evidence) {
        return new InvestigationRequest("INC-1", "backlog", new TimeWindow(START, END), evidence);
    }

    static InvestigationRequest without(String id) {
        List<EvidenceItem> l = new ArrayList<>(request().evidence());
        l.removeIf(e -> e.id().equals(id));
        return with(l);
    }

    static IncidentAssessment assessment() {
        return new IncidentAssessment("INC-1", "Backlog caused by bulk async starts.", Severity.HIGH,
                List.of(new Finding("Starts rose to 400/min", ClaimKind.FACT, List.of("E-MET-1")),
                        new Finding("Batch import queued 12k instances", ClaimKind.FACT, List.of("E-LOG-1"))),
                List.of(new Hypothesis(CauseCategory.LOAD_SURGE, "bulk import", 0.8, List.of("E-MET-1", "E-LOG-1"), List.of()),
                        new Hypothesis(CauseCategory.EXECUTOR_CONFIG_REGRESSION, "deploy", 0.15, List.of("E-DEP-1"),
                                List.of("E-LOG-1"))),
                List.of("Why was the import not throttled?"),
                List.of(new Recommendation("Compare job creation vs acquisition rate over time", "confirm surge", true)),
                0.75, false);
    }

    static IncidentAssessment with(java.util.function.UnaryOperator<IncidentAssessmentBuilder> f) {
        return f.apply(new IncidentAssessmentBuilder(assessment())).build();
    }

    /** Tiny copy-with helper for the record. */
    static final class IncidentAssessmentBuilder {
        String summary; List<Finding> findings; List<Hypothesis> hypotheses; List<Recommendation> recs;
        double confidence; boolean remediation; Severity severity; List<String> unknowns; String id;
        IncidentAssessmentBuilder(IncidentAssessment a) {
            id = a.incidentId(); summary = a.summary(); findings = a.findings(); hypotheses = a.hypotheses();
            recs = a.recommendations(); confidence = a.confidence(); remediation = a.remediationExecuted();
            severity = a.severity(); unknowns = a.unknowns();
        }
        IncidentAssessmentBuilder summary(String s) { summary = s; return this; }
        IncidentAssessmentBuilder findings(List<Finding> f) { findings = f; return this; }
        IncidentAssessmentBuilder hypotheses(List<Hypothesis> h) { hypotheses = h; return this; }
        IncidentAssessmentBuilder recs(List<Recommendation> r) { recs = r; return this; }
        IncidentAssessmentBuilder confidence(double c) { confidence = c; return this; }
        IncidentAssessmentBuilder remediation(boolean r) { remediation = r; return this; }
        IncidentAssessment build() {
            return new IncidentAssessment(id, summary, severity, findings, hypotheses, unknowns, recs, confidence, remediation);
        }
    }
}
