# Workflow Incident Investigator

A small, portfolio-ready, **read-only** incident investigation assistant over a *fictional* workflow-engine
job-backlog incident. It reads supplied alerts, metrics, logs, deployment notes and a runbook, and returns an
evidence-cited diagnosis with safe, read-only recommendations. It never performs remediation.

The same workflow is implemented twice to compare frameworks:

| | Java (primary) | Python (comparison) |
|---|---|---|
| Stack | Spring Boot 4 + Spring AI 2 + Amazon Bedrock | LangGraph + langchain-aws (Bedrock) + LangSmith |
| Orchestration | `InvestigationService` + virtual threads | `StateGraph` with fan-out/fan-in edges |
| Tracing | per-step `TraceRecorder`, correlation id in MDC/logs, `X-Investigation-Id` | LangSmith node traces + per-step `StepTrace` |
| Folder | [`java/`](java) | [`python/`](python) |

```
request -> validate -> triage -> runtime analyst ‖ change analyst -> verifier -> deterministic validation -> assessment
```

Contract shared by both: [`contracts/investigation-contract.md`](contracts/investigation-contract.md).
Scenarios (4, with expected answers kept in separate `incident.yaml` files that are never sent to the model):
[`scenarios/`](scenarios). Design comparison and findings: [`docs/architecture-comparison.md`](docs/architecture-comparison.md).

Using a separate Bedrock profile/policy/cost tag for this project: [`docs/bedrock-setup.md`](docs/bedrock-setup.md).

## Run the Java service

Needs a JDK (21+; the plan's Java 25 works - change `java.version` in `pom.xml`) and Maven.

```bash
cd java
mvn test                                  # 33 deterministic tests, no AWS needed
export AWS_REGION=us-east-2               # credentials via AWS CLI profile / env vars; never commit them
export BEDROCK_MODEL_ID=<chat model or inference profile enabled in your account>
mvn spring-boot:run
curl -X POST localhost:8080/api/incidents/investigate -H 'content-type: application/json' \
     --data @../scenarios/camunda-job-backlog/sample-request.json
# live evaluation of all scenarios (prints per-dimension scores):
mvn spring-boot:run -Dspring-boot.run.arguments="--incident.eval.enabled=true --spring.main.web-application-type=none"
```

## Run the Python graph

```bash
cd python && python -m venv .venv && . .venv/bin/activate && pip install -e '.[dev]'
pytest                                    # 16 deterministic tests, no AWS/LangSmith needed
export AWS_REGION=us-east-2 BEDROCK_MODEL_ID=<same model>
export LANGSMITH_TRACING=true LANGSMITH_API_KEY=<key> LANGSMITH_PROJECT=incident-investigator   # optional
python -m incident_graph investigate ../scenarios/camunda-job-backlog/sample-request.json
python -m incident_graph eval                 # local scoring
python -m incident_graph eval --langsmith     # dataset + experiment in LangSmith
```

## Boundaries (first version)

No production integrations, no automatic restart/rollback/throttling/executor changes/tickets/notifications,
no agent-to-agent chat, no vector DB, no UI. Personal project on fictional data; unrelated to any employer system.
