package dev.incident.eval;

import dev.incident.domain.CauseCategory;
import dev.incident.domain.InvestigationRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/** Reads scenarios/&lt;name&gt;/{sample-request.json, incident.yaml}. Request and expectation stay in separate files. */
public class ScenarioLoader {
    private final JsonMapper json = JsonMapper.builder().build();

    public List<Scenario> loadAll(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("sample-request.json")) && Files.exists(p.resolve("incident.yaml")))
                    .sorted().map(this::load).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Scenario load(Path scenarioDir) {
        try {
            InvestigationRequest request = json.readValue(
                    Files.readString(scenarioDir.resolve("sample-request.json")), InvestigationRequest.class);
            Map<String, Object> y = new Yaml().load(Files.readString(scenarioDir.resolve("incident.yaml")));
            ExpectedAnswer expected = new ExpectedAnswer(
                    (String) y.get("incidentId"),
                    CauseCategory.valueOf((String) y.get("expectedRootCause")),
                    strings(y.get("requiredEvidence")),
                    strings(y.get("mustNotConcludeAsPrimary")).stream().map(CauseCategory::valueOf).toList(),
                    (String) y.get("notes"));
            return new Scenario(scenarioDir.getFileName().toString(), request, expected);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> strings(Object o) {
        return o == null ? List.of() : ((List<?>) o).stream().map(String::valueOf).toList();
    }
}
