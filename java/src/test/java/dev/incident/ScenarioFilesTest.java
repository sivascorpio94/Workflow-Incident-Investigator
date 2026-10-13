package dev.incident;

import static org.assertj.core.api.Assertions.assertThat;

import dev.incident.eval.Scenario;
import dev.incident.eval.ScenarioLoader;
import dev.incident.validation.RequestValidator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The shipped scenarios must load, pass request validation, and keep expected causes out of the request. */
class ScenarioFilesTest {
    private final List<Scenario> scenarios = new ScenarioLoader().loadAll(Path.of("../scenarios"));

    @Test void allScenariosLoadAndAreValidRequests() {
        assertThat(scenarios).extracting(Scenario::name).containsExactlyInAnyOrder(
                "camunda-job-backlog", "executor-capacity-regression", "database-saturation", "retry-storm",
                "insufficient-evidence", "surge-exhausts-db");
        var validator = new RequestValidator();
        scenarios.forEach(s -> validator.validate(s.request()));
    }

    @Test void expectedCausesDifferAcrossScenarios() {
        assertThat(scenarios.stream().map(s -> s.expected().expectedRootCause()).distinct().count()).isEqualTo(5);
    }

    @Test void harderScenariosCarryTheirExtraExpectations() {
        var undetermined = scenarios.stream().filter(s -> s.name().equals("insufficient-evidence")).findFirst().orElseThrow();
        assertThat(undetermined.expected().maxConfidence()).isEqualTo(0.5);
        var compound = scenarios.stream().filter(s -> s.name().equals("surge-exhausts-db")).findFirst().orElseThrow();
        assertThat(compound.expected().mustMention()).containsExactly(dev.incident.domain.CauseCategory.DATABASE_SATURATION);
    }

    @Test void expectedEvidenceExistsAndAnswerIsNotLeakedIntoRequest() {
        for (Scenario s : scenarios) {
            var ids = s.request().evidence().stream().map(e -> e.id()).toList();
            assertThat(ids).containsAll(s.expected().requiredEvidence());
            String all = s.request().evidence().stream().map(e -> e.content()).reduce("", String::concat);
            assertThat(all).doesNotContain(s.expected().expectedRootCause().name());
        }
    }
}
