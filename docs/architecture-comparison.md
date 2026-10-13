# Spring AI vs LangGraph on this workflow

Status: both implementations are built, tested offline with stubbed models (Java 37 tests, Python 20), and have been
run live against Bedrock (see the results sections). Sections that say "pending" or "to measure" are not yet measured.

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

## Round 2: harder scenarios, stricter scorer, tighter prompts

Changes made after round 1, all generic rather than tuned to one incident:
- **Two new scenarios:** `insufficient-evidence` (only a backlog series exists; correct answer is `UNDETERMINED` with
  confidence <= 0.5) and `surge-exhausts-db` (a bulk start is the trigger, DB pool exhaustion amplifies it; best answer
  ranks `LOAD_SURGE` first and names `DATABASE_SATURATION`; ranking DB first earns partial credit).
- **Scorer 6 -> 8 dimensions:** added `factDiscipline` (FACT findings must not use inference wording such as
  "indicating", "ruling out", "caused") and `concision` (more than 5 unknowns or recommendations is penalised);
  `maxConfidence` and `mustMention` expectations per scenario. Round 1's 1.00 was on the 6-dimension rubric, so round 2
  scores are **not** directly comparable; re-run round 1's four scenarios under the new rubric for a fair comparison.
- **Prompts:** FACT vs HYPOTHESIS rules, exact metric names, check timestamps before asserting before/after, an explicit
  UNDETERMINED rule, trigger-vs-amplifier ranking, and length caps. Mirrored in `Prompts.java` and `prompts.py`.

The `surge-exhausts-db` expectation is a judgement call (a reasonable engineer could rank the database first); it is
documented in that scenario's `notes` and scored with partial credit rather than as a hard failure.

## Round 2 results (live, 2026-10-13; superseded by round 3 below, 6-dimension-era prompts)

Same model (`us.anthropic.claude-haiku-4-5-20251001-v1:0`), same 8-dimension rubric, same prompts, one run each.
Cells are overall score; dimensions below 1.00 are listed.

| Scenario | Expected = got? | Java + Spring AI | Python + LangGraph |
|---|---|---|---|
| camunda-job-backlog | yes (both) | 1.00 | 0.94 (alternatives 0.70, factDiscipline 0.86) |
| database-saturation | yes (both) | 1.00 | 1.00 |
| executor-capacity-regression | yes (both) | 1.00 | 0.96 (factDiscipline 0.67) |
| insufficient-evidence | yes (both, `UNDETERMINED`) | 0.98 (factDiscipline 0.83) | 1.00 |
| retry-storm | yes (both) | 0.97 (factDiscipline 0.78) | 0.98 (factDiscipline 0.86) |
| surge-exhausts-db | yes (both, `LOAD_SURGE` first) | 1.00 | 1.00 |
| **Mean** | **12 / 12 correct** | **0.99** | **0.98** |

What this does and does not show:
- **Diagnosis is not framework-dependent.** Both pipelines picked the right cause everywhere, including the
  `UNDETERMINED` case and the trigger-versus-amplifier case. The 0.01 gap in the mean is within what one run of
  an LLM varies by; it is not evidence that one framework is better.
- **Length caps worked:** `concision` is 1.00 in all 12 runs (round 1's baseline had 9 unknowns and 8 recommendations).
- **Fact discipline is the weak spot (0.67-1.00):** the model still sometimes labels interpretive claims FACT. The
  check is a word-list heuristic, so some of this may be false positives; read the answers before acting on it.
- **Python's one `alternatives` miss** (camunda baseline, 0.70): no hypothesis cited contradicting evidence.
- Single runs, synthetic scenarios, rule-based scoring, one cheap model. Latency and token use were not compared
  across implementations in this round.

## Observed differences while building and running both

- **Credentials.** Java ran once the stale `AWS_PROFILE` / expiry variables were unset. Python's boto stack read
  `~/.aws/config` even when an API key was set and failed on an expired `aws login` profile; it needed a small
  workaround in `llm.py` (covered by an offline test).
- **Orchestration.** Java parallelism is explicit code (virtual threads + join); LangGraph's is two edges and a
  reducer. Both tested for real overlap with a rendezvous in the stub model.
- **Typed output.** Both produced valid typed output on every call in these runs; neither needed retries.
- **Tracing.** Java: hand-built per-step trace and correlation id, returned in the response. Python: per-step trace in
  the response; LangSmith tracing is wired but **not yet exercised**.

## Round 3: fixes after reading a real answer in the UI

Reading a live Java answer in the UI exposed problems the round-2 scorer missed (all scored ~1.00):
- The summary said the 10:05 deployment came "3 minutes after symptom onset"; onset was ~09:40, so it was ~25 minutes
  after onset and 3 minutes after the *alert* (alert time confused with onset time).
- The runner-up `EXECUTOR_CONFIG_REGRESSION` was at 72% against 85% for the diagnosis, with no evidence of a reduced or
  misconfigured executor (the deployment raised capacity) and no contradicting evidence cited.
- Interpretation ("indicating the thread pool was the primary bottleneck") appeared in the summary, which the
  fact-discipline check does not inspect.

Changes (generic, not tied to one scenario):
- **Application-computed timeline** of evidence timestamps, sorted, added to analyst and verifier prompts, with
  rules to keep alert time, symptom onset and change time separate and to compute gaps from the timeline.
- **Prompt rules:** a contradicted hypothesis gets confidence <= 0.2 and must cite the contradicting evidence; check the
  direction of each change; `EXECUTOR_CONFIG_REGRESSION` only when settings were reduced or misconfigured, while
  "fixed capacity too small for higher load" is `LOAD_SURGE`; interpretation belongs in hedged hypotheses, not summaries.
- **New score dimension `separation`** (9 dimensions now): a runner-up within 0.1 of the diagnosis scores 0, 0.3 or more
  scores 1; skipped for under-determined and trigger-plus-amplifier scenarios. The 85% vs 72% answer above scores 0.15.

**First live check after these changes (one run, Java, baseline scenario, in the UI):** timing is now stated correctly
("3 min after the alert, 25 min after symptom onset"); the runner-up dropped from 72% to 35% (`OTHER`, with the model
saying no evidence supports it), so `separation` would score 1.0; all recommendations read-only. Remaining: it returned
15 findings against a prompt cap of 8 (the scorer now counts findings in `concision`), and the summary still ends with a
mild conclusion ("ruling itself out as root cause"). The model also questioned why acquisition stayed near 500/min while
starts were 420/min; that was a gap in the synthetic data (jobs per process start were not stated), so the baseline
evidence now says each start creates roughly 3-4 async jobs. One run is an anecdote, not a measurement.

Not fixed by these changes: there is still no deterministic check of timing claims in prose. Scores from this round are
not comparable with round 2 (different rubric and prompts); the live results are in the next section.

## Round 3 results (live, 2026-10-13): final prompts, 9-dimension rubric, one run each

Model `us.anthropic.claude-haiku-4-5-20251001-v1:0`, temperature 0.1. Overall score per scenario; dimensions below 1.00
are listed (`fact` = factDiscipline, `sep` = separation, `alt` = alternatives).

| Scenario | Correct cause? | Java + Spring AI | Python + LangGraph |
|---|---|---|---|
| camunda-job-backlog | yes (both) | 0.99 (fact 0.88) | 0.90 (fact 0.83, sep 0.25) |
| database-saturation | yes (both) | 0.97 (fact 0.75) | 0.99 (fact 0.88) |
| executor-capacity-regression | yes (both) | 1.00 | 0.88 (fact 0.75, sep 0.20) |
| insufficient-evidence | yes (both, `UNDETERMINED`) | 0.98 (fact 0.83) | 0.98 (fact 0.80) |
| retry-storm | yes (both) | 0.94 (sep 0.50) | 0.89 (sep 0.00) |
| surge-exhausts-db | yes (both, `LOAD_SURGE` first) | 1.00 | 0.97 (alt 0.85, fact 0.86) |
| **Mean** | **12 / 12 correct** | **0.98** | **0.93** |

Java eval wall time: 3 min 12 s for all six scenarios (about 30 s each; verifier 17-20 s, analysts 7-12 s in parallel).
Python eval wall time was not recorded.

How to read this:
- **Diagnosis quality is the same:** 12 of 12 correct, including the `UNDETERMINED` case and the trigger-plus-amplifier
  case. Same model, same prompts, same contract.
- **The 0.05 gap in the mean is not evidence that Java is better.** It comes almost entirely from `separation`: in the
  Python run three scenarios had a runner-up within 0.1-0.3 of the diagnosis (scores 0.25, 0.20, 0.00), in the Java run
  one did (0.50). With identical prompts and one run per scenario, that is most likely model run-to-run variation in the
  confidence numbers. Repeat runs (say 5 per scenario, reporting mean and range) would be needed to claim a framework
  effect, and I would not expect one.
- **`separation` is a blunt measure.** A low value means the model gave a close second hypothesis; it does not say whether
  that was wrong. Some scenarios (e.g. a retry storm amplified by a policy change) legitimately have two plausible
  contributors. Read the answers before judging.
- **Fact discipline remains the weakest dimension** (0.75-1.00): the model still labels some interpretation as FACT.
- **Overall scores are lower than round 2's 0.98-0.99 by construction:** the rubric gained `separation` and a findings
  cap, so the two rounds are not comparable. Round 2's perfect-looking scores were partly a lenient rubric.

## Still to measure

Repeat runs for variance (e.g. 5 per scenario, mean and range), a larger model, per-step latency and token totals for
Python. LangSmith tracing and an experiment (`python -m incident_graph eval --langsmith`) ran once on 2026-10-13
(round 2 prompts); its scores were not transcribed here.
