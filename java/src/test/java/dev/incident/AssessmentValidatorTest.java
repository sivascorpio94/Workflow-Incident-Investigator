package dev.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.incident.domain.*;
import dev.incident.validation.AssessmentValidationException;
import dev.incident.validation.AssessmentValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssessmentValidatorTest {
    private final AssessmentValidator v = new AssessmentValidator();
    private final InvestigationRequest req = Fixtures.request();

    private void assertRejected(IncidentAssessment a, String fragment) {
        assertThatThrownBy(() -> v.validate(a, req)).isInstanceOf(AssessmentValidationException.class)
                .satisfies(e -> assertThat(((AssessmentValidationException) e).violations())
                        .anyMatch(s -> s.contains(fragment)));
    }

    @Test void acceptsValidAssessment() {
        assertThatCode(() -> v.validate(Fixtures.assessment(), req)).doesNotThrowAnyException();
    }

    @Test void rejectsUnknownEvidenceIdInFinding() {
        assertRejected(Fixtures.with(b -> b.findings(List.of(new Finding("x", ClaimKind.FACT, List.of("E-NOPE-1"))))),
                "unknown evidence id: E-NOPE-1");
    }

    @Test void rejectsUnknownEvidenceIdInHypothesis() {
        assertRejected(Fixtures.with(b -> b.hypotheses(List.of(
                new Hypothesis(CauseCategory.LOAD_SURGE, "d", 0.5, List.of("E-GHOST"), List.of())))), "E-GHOST");
    }

    @Test void rejectsFactWithoutEvidence() {
        assertRejected(Fixtures.with(b -> b.findings(List.of(new Finding("bare fact", ClaimKind.FACT, List.of())))),
                "FACT finding cites no evidence");
    }

    @Test void allowsHypothesisWithoutEvidence() {
        assertThatCode(() -> v.validate(Fixtures.with(b ->
                b.findings(List.of(new Finding("maybe", ClaimKind.HYPOTHESIS, List.of())))), req)).doesNotThrowAnyException();
    }

    @Test void rejectsRemediationExecutedFlag() {
        assertRejected(Fixtures.with(b -> b.remediation(true)), "remediationExecuted must be false");
    }

    @Test void rejectsNonReadOnlyRecommendation() {
        assertRejected(Fixtures.with(b -> b.recs(List.of(new Recommendation("Restart pods", "r", false)))), "not read-only");
    }

    @Test void rejectsTextClaimingRemediation() {
        assertRejected(Fixtures.with(b -> b.summary("I restarted the engine and the backlog cleared.")), "claim remediation");
        assertRejected(Fixtures.with(b -> b.summary("The pods have been restarted.")), "claim remediation");
    }

    @Test void allowsRecommendingAndDescribingChangesWithoutClaimingThem() {
        assertThatCode(() -> v.validate(Fixtures.with(b -> b
                .summary("Pending jobs increased sharply; the deployment increased maxPoolSize.")
                .recs(List.of(new Recommendation("A human should consider whether to throttle the importer", "r", true)))),
                req)).doesNotThrowAnyException();
    }

    @Test void rejectsOutOfRangeConfidenceAndNoHypotheses() {
        assertRejected(Fixtures.with(b -> b.confidence(1.4)), "must be within [0,1]");
        assertRejected(Fixtures.with(b -> b.hypotheses(List.of())), "at least one hypothesis");
    }

    @Test void analystMayOnlyReviewSubmittedEvidence() {
        var bad = new AnalystReport(List.of("E-MET-1", "E-FAKE"), List.of(), List.of());
        assertThatThrownBy(() -> v.validateAnalyst("runtime-analyst", bad, req))
                .isInstanceOf(AssessmentValidationException.class);
        assertThatCode(() -> v.validateAnalyst("runtime-analyst",
                new AnalystReport(List.of("E-MET-1"), List.of(), List.of()), req)).doesNotThrowAnyException();
    }
}
