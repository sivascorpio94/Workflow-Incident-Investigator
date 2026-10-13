package dev.incident.api;

import dev.incident.domain.InvestigationRequest;
import dev.incident.eval.Scenario;
import dev.incident.eval.ScenarioLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only endpoints for the demo UI: the bundled scenarios (with their expected answer, for reveal) and a label. */
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"}, exposedHeaders = "X-Investigation-Id")
public class ScenarioController {

    public record ScenarioView(String name, InvestigationRequest request, Expected expected) {}

    public record Expected(String rootCause, String notes) {}

    private final Path scenariosDir;
    private final String label;

    public ScenarioController(@Value("${incident.scenarios.dir:../scenarios}") String dir,
                              @Value("${incident.backend-label:Java \u00b7 Spring AI \u00b7 Amazon Bedrock}") String label) {
        this.scenariosDir = Path.of(dir);
        this.label = label;
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of("backend", label);
    }

    @GetMapping("/scenarios")
    public List<ScenarioView> scenarios() {
        if (!Files.isDirectory(scenariosDir)) {
            return List.of();
        }
        return new ScenarioLoader().loadAll(scenariosDir).stream().map(ScenarioController::view).toList();
    }

    private static ScenarioView view(Scenario s) {
        return new ScenarioView(s.name(), s.request(),
                new Expected(s.expected().expectedRootCause().name(), s.expected().notes()));
    }
}
