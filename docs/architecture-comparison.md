# Spring AI vs LangGraph on this workflow

Status: both implementations are built and unit/integration-tested with stubbed models (Java 33 tests, Python 16).
**Not yet done:** a live Bedrock run and measured latency/token/cost numbers. Sections marked *(to measure)* must be
filled in from real runs; nothing below is an observed model result.

## Same behaviour, different shape

| Concern | Spring AI (Java) | LangGraph (Python) |
|---|---|---|
| Orchestration clarity | Plain method calls; parallelism is explicit `CompletableFuture`s joined before the verifier. Easy to read, control flow lives in code. | Declarative graph: nodes + edges; fan-out/fan-in is two `add_edge` lines. The topology is visible and renderable, but state flow is indirect (reducers, partial updates). |
| Typed output | `responseEntity(Record.class)` via Jackson; compile-time types. | `with_structured_output(PydanticModel)`; runtime validation, aliases for camelCase wire format. |
| Parallel execution | Virtual-thread executor, hand-written join. | Built into the superstep model; needs a reducer (`operator.add`) for shared list state. |
| Application checks | `AssessmentValidator` after the verifier, mapped to HTTP 422. | Same rules in a final `validate_assessment` node; raised as `AssessmentValidationError`. |
| Error recovery | Typed exceptions -> RFC 7807 responses; provider message never echoed. | Same exception types; no HTTP layer in this version (CLI only). |
| Tracing | Hand-rolled `TraceRecorder` (step, duration, outcome, tokens, MDC correlation id). Portable but you build it. | LangSmith traces every node automatically with inputs/outputs; fastest path to a trace UI, tied to a SaaS. |
| Evaluation | Deterministic `Scorer` + live `EvalRunner`. | Same rubric + LangSmith dataset/experiment (`evaluate`). Better tooling for comparing runs. |
| Testing without a model | `ModelClient` interface, stub bean, MockMvc. | `StructuredLLM` callable, fake class. Equivalent effort. |

## Design decisions worth explaining in a demo

1. **The model sees evidence, never the answer.** Expected causes live in `scenarios/*/incident.yaml`; a test asserts
   they are not in requests or prompts.
2. **Timing is a clue, not proof.** The baseline has the deployment *after* the backlog starts and it *raises* capacity;
   the retry-storm deployment is 2h earlier; the DB-saturation deployment is benign. Each scenario has a different
   best-supported cause so the assistant cannot pass by repeating one diagnosis.
3. **Model output is untrusted.** Citations must exist, FACTs must cite, confidence is range-checked, recommendations
   must be `readOnly`, and text claiming remediation was performed is rejected - deterministically, not by prompt.
4. **A single seam (`ModelClient` / `StructuredLLM`)** keeps orchestration, validation and scoring testable offline.

## Known limitations

- The remediation-claim check is a regex over a few verbs; it will miss creative phrasing. It is a backstop to the
  prompt, not a proof.
- The scorer is rule-based (category match, citation overlap, confidence bands). It cannot judge prose quality.
- Prompts are duplicated across languages; keep them in sync by hand (or externalise later).
- Sandbox used for development had JDK 21, so `java.version` is 21 rather than the plan's 25.

## Measured: Java + Spring AI + Bedrock (run 2026-10-13)

Model `us.anthropic.claude-haiku-4-5-20251001-v1:0`, temperature 0.1, region us-east-2, one run per scenario.

| Scenario | Expected | Got | Overall |
|---|---|---|---|
| camunda-job-backlog | LOAD_SURGE | LOAD_SURGE | 1.00 |
| database-saturation | DATABASE_SATURATION | DATABASE_SATURATION | 1.00 |
| executor-capacity-regression | EXECUTOR_CONFIG_REGRESSION | EXECUTOR_CONFIG_REGRESSION | 1.00 |
| retry-storm | RETRY_STORM | RETRY_STORM | 1.00 |

Mean 1.00; whole eval 2 min 20 s (about 35 s per investigation, ~6.6k prompt + ~4.9k completion tokens each; the
verifier is ~60-70% of latency, analysts run in parallel at ~6-10 s).

**How to read this honestly.** A 1.00 on four hand-written, fairly clear-cut scenarios mostly shows the harness works
and the model is not fooled by the red-herring deployments. It does not show the assistant is good. The rule-based
scorer cannot see prose errors; inspection of the baseline answer found a wrong claim (the 10:05 deployment described
as "incomplete at symptom onset" although it came after onset), a metric mix-up (starts/min called acquisition rate),
inferences labelled FACT, and over-long output (9 unknowns, 8 recommendations). Harder scenarios (two interacting
causes, missing evidence -> UNDETERMINED) and stricter scoring are the next step. Single run per scenario, so no
variance estimate yet.

## Still to measure

Python/LangGraph run on the same scenarios and model, repeat runs for variance, and a larger model for comparison.
