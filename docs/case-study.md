# Case study: a read-only, multi-agent incident investigator

*Personal project on fictional data. It is not connected to, and makes no claims about, any employer's systems.*

## Problem
During a workflow-engine incident (growing job backlog, rising heap) the evidence is scattered across alerts, metrics,
logs, deployment notes and a runbook, and a deployment close in time is easy to blame. I wanted an assistant that
correlates the evidence, says which cause is best supported and how sure it is, and **never touches production**.

## Design
A bounded, application-orchestrated workflow, not free-form agent chat:

`validate request -> triage -> (runtime analyst || change analyst) -> verifier -> deterministic checks`

- Agents see only the evidence relevant to them and have **no tools**; the application owns control flow.
- The model's answer is untrusted. The application rejects unknown evidence IDs, uncited "facts", out-of-range
  confidence, non-read-only recommendations, and text claiming a fix was applied.
- The expected answers live in separate files and are never sent to the model (a test enforces this).
- Built twice for comparison: Java (Spring Boot 4, Spring AI, Bedrock) and Python (LangGraph, Bedrock, LangSmith hooks),
  sharing one request/response contract, scenarios and scoring rubric.

## Evaluation
Six synthetic scenarios with different best-supported causes: a load surge, an executor-config regression, database
saturation, a retry storm, a surge that exhausts the database (trigger plus amplifier), and a case with too little
evidence where the right answer is `UNDETERMINED`. Each is scored on eight rule-based dimensions: citation accuracy,
root cause, confidence calibration, alternatives considered, safe recommendations, format validity, fact-versus-inference
discipline, and concision.

## Results (Claude Haiku 4.5, one run each, final prompts and rubric)
Both implementations chose the correct primary cause in 12 of 12 runs (6 scenarios x 2). The mean score on the
nine-dimension rubric was 0.98 for Java and 0.93 for Python; the gap comes from how close the runner-up hypothesis was
and, with identical prompts and one run each, is most likely model variance rather than a framework effect. Details and
caveats: [`architecture-comparison.md`](architecture-comparison.md).

## What I learned
- The first rubric scored a flawed answer 1.00 (a wrong deployment-timing claim, a metric mix-up, inferences labelled as
  facts, nine unknowns). Reading the output, not just the score, is what led to the stricter rubric and tighter prompts.
- Concision improved to 1.00 after adding length caps; fact discipline is still the weakest dimension.
- Reading real answers in the UI found flaws the scores missed (a wrong deployment-timing claim, a runner-up at 72%
  against 85%). Fixing them took an application-computed timeline, clearer category rules and a `separation` check.
- Most of the real friction was credentials and configuration, not the AI logic.

## Limits
Synthetic data, single runs, a word-list heuristic for "inference", no variance estimate, one cheap model, and no
production integrations by design. The next steps are repeat runs, a larger-model comparison, and LangSmith traces.
