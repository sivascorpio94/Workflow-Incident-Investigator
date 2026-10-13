package dev.incident;

import static org.assertj.core.api.Assertions.assertThat;

import dev.incident.domain.*;
import dev.incident.eval.ExpectedAnswer;
import dev.incident.eval.Score;
import dev.incident.eval.Scorer;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScorerTest {
    private final Scorer scorer = new Scorer();
    private final InvestigationRequest req = Fixtures.request();
    private final ExpectedAnswer expected = new ExpectedAnswer("INC-1", CauseCategory.LOAD_SURGE,
            List.of("E-LOG-1", "E-MET-1"), List.of(CauseCategory.EXECUTOR_CONFIG_REGRESSION), "");

    @Test void goodAnswerScoresHigh() {
        Score s = scorer.score(Fixtures.assessment(), req, expected);
        assertThat(s.dimensions().get(Scorer.ROOT_CAUSE)).isEqualTo(1.0);
        assertThat(s.dimensions().get(Scorer.CITATIONS)).isEqualTo(1.0);
        assertThat(s.overall()).isGreaterThan(0.9);
    }

    @Test void trapDiagnosisScoresZeroOnRootCauseAndIsPenalisedForConfidence() {
        var trap = Fixtures.with(b -> b.hypotheses(List.of(
                new Hypothesis(CauseCategory.EXECUTOR_CONFIG_REGRESSION, "deploy did it", 0.9, List.of("E-DEP-1"), List.of()))).confidence(0.9));
        Score s = scorer.score(trap, req, expected);
        assertThat(s.dimensions().get(Scorer.ROOT_CAUSE)).isZero();
        assertThat(s.dimensions().get(Scorer.CALIBRATION)).isZero();
        assertThat(s.dimensions().get(Scorer.ALTERNATIVES)).isZero();
        assertThat(s.overall()).isLessThan(0.7); // 8 dimensions now; wrong+overconfident still far below a good answer (>0.9)
    }

    @Test void missingRequiredEvidenceLowersCitationScore() {
        var thin = Fixtures.with(b -> b.findings(List.of()).hypotheses(List.of(
                new Hypothesis(CauseCategory.LOAD_SURGE, "x", 0.7, List.of("E-MET-1"), List.of()))));
        assertThat(scorer.score(thin, req, expected).dimensions().get(Scorer.CITATIONS)).isEqualTo(0.5);
    }

    @Test void failedInvestigationScoresZero() {
        assertThat(scorer.score(null, req, expected).overall()).isZero();
    }

    @Test void factDisciplineAndConcisenessPenaliseSloppyAnswers() {
        var sloppy = Fixtures.with(b -> b
                .findings(List.of(
                        new Finding("DB is fine, ruling out database contention", ClaimKind.FACT, List.of("E-MET-1")),
                        new Finding("Pool saturated indicating a bottleneck", ClaimKind.FACT, List.of("E-MET-1")),
                        new Finding("Starts rose to 400/min", ClaimKind.FACT, List.of("E-MET-1"))))
                .recs(java.util.stream.IntStream.range(0, 8)
                        .mapToObj(i -> new Recommendation("a" + i, "r", true)).toList()));
        Score s = scorer.score(sloppy, req, expected);
        assertThat(s.dimensions().get(Scorer.FACT_DISCIPLINE)).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.dimensions().get(Scorer.CONCISION)).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(scorer.score(Fixtures.assessment(), req, expected).dimensions().get(Scorer.FACT_DISCIPLINE)).isEqualTo(1.0);
    }

    @Test void underDeterminedScenarioRewardsLowConfidenceAndPunishesAGuess() {
        var cap = new ExpectedAnswer("INC-1", CauseCategory.UNDETERMINED, List.of("E-MET-1"),
                List.of(CauseCategory.LOAD_SURGE), "", 0.5, List.of());
        var honest = Fixtures.with(b -> b.confidence(0.3).hypotheses(List.of(
                new Hypothesis(CauseCategory.UNDETERMINED, "not enough evidence", 0.3, List.of("E-MET-1"), List.of()),
                new Hypothesis(CauseCategory.LOAD_SURGE, "possible", 0.2, List.of(), List.of("E-LOG-1")))));
        Score h = scorer.score(honest, req, cap);
        assertThat(h.dimensions().get(Scorer.ROOT_CAUSE)).isEqualTo(1.0);
        assertThat(h.dimensions().get(Scorer.CALIBRATION)).isEqualTo(1.0);
        Score g = scorer.score(Fixtures.with(b -> b.confidence(0.85)), req, cap); // confident LOAD_SURGE
        assertThat(g.dimensions().get(Scorer.ROOT_CAUSE)).isZero();
        assertThat(g.dimensions().get(Scorer.CALIBRATION)).isZero();
    }

    @Test void mustMentionRewardsNamingTheAmplifier() {
        var compound = new ExpectedAnswer("INC-1", CauseCategory.LOAD_SURGE, List.of("E-MET-1"),
                List.of(), "", null, List.of(CauseCategory.DATABASE_SATURATION));
        var both = Fixtures.with(b -> b.hypotheses(List.of(
                new Hypothesis(CauseCategory.LOAD_SURGE, "trigger", 0.7, List.of("E-MET-1"), List.of("E-DEP-1")),
                new Hypothesis(CauseCategory.DATABASE_SATURATION, "amplifier", 0.6, List.of("E-MET-1"), List.of()))));
        var only = Fixtures.with(b -> b.hypotheses(List.of(
                new Hypothesis(CauseCategory.LOAD_SURGE, "trigger", 0.7, List.of("E-MET-1"), List.of("E-DEP-1")),
                new Hypothesis(CauseCategory.RETRY_STORM, "other", 0.1, List.of(), List.of()))));
        assertThat(scorer.score(both, req, compound).dimensions().get(Scorer.ALTERNATIVES)).isEqualTo(1.0);
        assertThat(scorer.score(only, req, compound).dimensions().get(Scorer.ALTERNATIVES)).isEqualTo(0.5);
    }

    @Test void separationPenalisesACloseRunnerUp() {
        var close = Fixtures.with(b -> b.hypotheses(List.of(
                new Hypothesis(CauseCategory.LOAD_SURGE, "surge", 0.85, List.of("E-MET-1", "E-LOG-1"), List.of()),
                new Hypothesis(CauseCategory.EXECUTOR_CONFIG_REGRESSION, "config", 0.72, List.of("E-DEP-1"), List.of("E-MET-1")))));
        assertThat(scorer.score(close, req, expected).dimensions().get(Scorer.SEPARATION)).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(scorer.score(Fixtures.assessment(), req, expected).dimensions().get(Scorer.SEPARATION)).isEqualTo(1.0); // 0.8 vs 0.15
        // not applied where a close second is legitimate (cap or amplifier scenarios)
        var amplifier = new ExpectedAnswer("INC-1", CauseCategory.LOAD_SURGE, List.of("E-MET-1"), List.of(), "", null,
                List.of(CauseCategory.EXECUTOR_CONFIG_REGRESSION));
        assertThat(scorer.score(close, req, amplifier).dimensions().get(Scorer.SEPARATION)).isEqualTo(1.0);
    }

    @Test void concisionAlsoCountsFindingsBeyondEight() {
        var many = Fixtures.with(b -> b.findings(java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> new Finding("f" + i, ClaimKind.HYPOTHESIS, List.of("E-MET-1"))).toList()));
        assertThat(scorer.score(many, req, expected).dimensions().get(Scorer.CONCISION))
                .isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9)); // 7 extra findings
    }
}
