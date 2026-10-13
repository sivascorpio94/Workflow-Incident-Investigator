# Screenshots to add

Save the files here with these exact names, then uncomment the matching line in the main README's "Screenshots" section.
Use a real run (not a stub). **Before saving, crop or blur**: AWS account IDs, API keys, your LangSmith organisation/project
URLs, browser tabs, and anything else personal.

| File | What to capture | How |
|---|---|---|
| `ui-compare.png` | Compare mode: Java and Python side by side with the "Both backends agree" banner | Open http://localhost:8080, tick *Compare with another backend*, run `camunda-job-backlog`, full-page screenshot |
| `ui-diagnosis.png` | One backend's result: severity, confidence, ranked hypotheses with evidence chips, FACT/HYPOTHESIS findings | Same run, zoom into one column |
| `ui-pipeline.png` | The "How it ran" section showing the two analysts side by side with timings and token counts | Scroll to the bottom of one column |
| `ui-error.png` | A rejected request with its violations (shows the guardrails) | Open *Request JSON*, change one evidence `id` to duplicate another, press Investigate (HTTP 400) |
| `ui-reveal.png` | "Reveal expected answer" showing a match | Press the button under the results |
| `eval-terminal.png` | The six-scenario score table from either eval | `mvn spring-boot:run -Dspring-boot.run.arguments="--incident.eval.enabled=true --spring.main.web-application-type=none"` or `python -m incident_graph eval` |
| `langsmith-trace.png` | One trace tree: triage, parallel analysts, verifier, validation | LangSmith -> Projects -> your project -> open a run |
| `langsmith-experiment.png` | The experiment table with the six scenarios and scores | LangSmith -> Datasets & Experiments -> `incident-investigator-scenarios` |

Optional: an architecture diagram image exported from the Mermaid diagram in the README (GitHub already renders the Mermaid,
so this is not required).
