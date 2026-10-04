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
        assertThat(s.overall()).isLessThan(0.6);
    }

    @Test void missingRequiredEvidenceLowersCitationScore() {
        var thin = Fixtures.with(b -> b.findings(List.of()).hypotheses(List.of(
                new Hypothesis(CauseCategory.LOAD_SURGE, "x", 0.7, List.of("E-MET-1"), List.of()))));
        assertThat(scorer.score(thin, req, expected).dimensions().get(Scorer.CITATIONS)).isEqualTo(0.5);
    }

    @Test void failedInvestigationScoresZero() {
        assertThat(scorer.score(null, req, expected).overall()).isZero();
    }
}
