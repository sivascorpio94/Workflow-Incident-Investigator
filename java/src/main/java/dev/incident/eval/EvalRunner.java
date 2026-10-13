package dev.incident.eval;

import dev.incident.domain.IncidentAssessment;
import dev.incident.workflow.InvestigationService;
import java.nio.file.Path;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Live evaluation over every scenario directory. Enable with
 * {@code --incident.eval.enabled=true --spring.main.web-application-type=none} (needs Bedrock access).
 */
@Component
@ConditionalOnProperty(name = "incident.eval.enabled", havingValue = "true")
public class EvalRunner implements CommandLineRunner {
    private final InvestigationService service;
    private final String dir;

    public EvalRunner(InvestigationService service, @Value("${incident.eval.dir:../scenarios}") String dir) {
        this.service = service;
        this.dir = dir;
    }

    @Override
    public void run(String... args) {
        Scorer scorer = new Scorer();
        double total = 0;
        var scenarios = new ScenarioLoader().loadAll(Path.of(dir));
        for (Scenario s : scenarios) {
            IncidentAssessment a = null;
            String note = "";
            try {
                a = service.investigate(s.request()).assessment();
            } catch (RuntimeException e) {
                note = " FAILED: " + e.getMessage();
            }
            Score score = scorer.score(a, s.request(), s.expected());
            total += score.overall();
            String got = a == null || a.hypotheses().isEmpty() ? "-" : a.hypotheses().get(0).category().name();
            System.out.printf("%-30s expected=%-28s got=%-28s %s%s%n", s.name(), s.expected().expectedRootCause(), got,
                    Scorer.describe(score), note);
        }
        System.out.printf("MEAN overall = %.2f over %d scenarios%n", total / Math.max(1, scenarios.size()), scenarios.size());
    }
}
