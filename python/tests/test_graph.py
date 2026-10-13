"""Deterministic tests: no Bedrock, no LangSmith, no network."""
import threading
from pathlib import Path

import pytest

from incident_graph import investigate
from incident_graph.errors import AssessmentValidationError, InvalidRequestError, ModelFailureError
from incident_graph.llm import LLMResult
from incident_graph.models import (AnalystReport, CauseCategory, ClaimKind, Finding, Hypothesis, IncidentAssessment,
                                   InvestigationRequest, Recommendation, Severity, TriageResult)
from incident_graph.scoring import load_scenarios, score

SCEN = Path(__file__).resolve().parents[2] / "scenarios"
SAMPLE = SCEN / "camunda-job-backlog" / "sample-request.json"


def request() -> InvestigationRequest:
    return InvestigationRequest.model_validate_json(SAMPLE.read_text())


def good_assessment(**over) -> IncidentAssessment:
    base = dict(
        incident_id="WF-2026-014", summary="Backlog follows a bulk async start surge.", severity=Severity.HIGH,
        findings=[Finding(claim="Starts rose to ~400/min", kind=ClaimKind.FACT, evidence_ids=["E-MET-1"])],
        hypotheses=[
            Hypothesis(category=CauseCategory.LOAD_SURGE, description="bulk import", confidence=0.8,
                       supporting_evidence_ids=["E-MET-1", "E-LOG-1", "E-MET-2"], contradicting_evidence_ids=[]),
            Hypothesis(category=CauseCategory.EXECUTOR_CONFIG_REGRESSION, description="deploy", confidence=0.1,
                       supporting_evidence_ids=["E-DEP-1"], contradicting_evidence_ids=["E-MET-2"])],
        unknowns=["why the import was not throttled"],
        recommendations=[Recommendation(action="Compare creation vs acquisition rates", rationale="r", read_only=True)],
        confidence=0.75, remediation_executed=False)
    base.update(over)
    return IncidentAssessment(**base)


class FakeLLM:
    def __init__(self, verifier=None, fail_step=None):
        self.prompts: dict[str, str] = {}
        self.verifier = verifier or good_assessment()
        self.fail_step = fail_step
        self.barrier = threading.Barrier(2, timeout=5)
        self.parallel = False

    def __call__(self, step, system, user, schema):
        self.prompts[step] = user
        if step == self.fail_step:
            raise RuntimeError("provider outage secret-token-123")
        if step == "triage":
            return LLMResult(TriageResult(alert_summary="Backlog", affected_system="wf-engine",
                                          observed_symptoms=["backlog"], alert_evidence_ids=["E-ALERT-1"]), 10, 5)
        if step in ("runtime-analyst", "change-analyst"):
            self.barrier.wait()  # only passes if both analysts are running at the same time
            self.parallel = True
            return LLMResult(AnalystReport(reviewed_evidence_ids=["E-ALERT-1"], findings=[], open_questions=[]))
        return LLMResult(self.verifier, 100, 50)


def test_happy_path_and_parallel_analysts():
    llm = FakeLLM()
    resp = investigate(request(), llm)
    assert resp.assessment.hypotheses[0].category == CauseCategory.LOAD_SURGE
    assert resp.assessment.remediation_executed is False
    assert llm.parallel
    assert [s.name for s in resp.steps][0] == "validate-request"
    assert {s.name for s in resp.steps} == {"validate-request", "triage", "runtime-analyst", "change-analyst",
                                            "verifier", "validate"}
    assert next(s for s in resp.steps if s.name == "verifier").prompt_tokens == 100


def test_analyst_evidence_routing():
    llm = FakeLLM()
    investigate(request(), llm)
    assert "[E-MET-1]" in llm.prompts["runtime-analyst"] and "[E-DEP-1]" not in llm.prompts["runtime-analyst"]
    assert "[E-DEP-1]" in llm.prompts["change-analyst"] and "[E-MET-1]" not in llm.prompts["change-analyst"]
    assert "[E-MET-1]" in llm.prompts["verifier"] and "[E-DEP-1]" in llm.prompts["verifier"]
    assert "expectedRootCause" not in " ".join(llm.prompts.values())


def test_unknown_evidence_id_rejected():
    bad = good_assessment(findings=[Finding(claim="x", kind=ClaimKind.FACT, evidence_ids=["E-MADE-UP"])])
    with pytest.raises(AssessmentValidationError) as ei:
        investigate(request(), FakeLLM(verifier=bad))
    assert any("E-MADE-UP" in v for v in ei.value.violations)


@pytest.mark.parametrize("over", [
    dict(remediation_executed=True),
    dict(recommendations=[Recommendation(action="Restart pods", rationale="r", read_only=False)]),
    dict(summary="I restarted the engine and it recovered."),
    dict(summary="The pods have been restarted."),
    dict(confidence=1.5),
    dict(hypotheses=[]),
    dict(findings=[Finding(claim="bare", kind=ClaimKind.FACT, evidence_ids=[])]),
])
def test_invariant_violations_rejected(over):
    with pytest.raises(AssessmentValidationError):
        investigate(request(), FakeLLM(verifier=good_assessment(**over)))


def test_describing_changes_without_claiming_them_is_allowed():
    ok = good_assessment(summary="Pending jobs increased; the deploy increased maxPoolSize.")
    investigate(request(), FakeLLM(verifier=ok))


def test_provider_failure_is_model_failure_without_leaking_message():
    with pytest.raises(ModelFailureError) as ei:
        investigate(request(), FakeLLM(fail_step="verifier"))
    assert "secret-token-123" not in str(ei.value) and "secret-token-123" not in " ".join(ei.value.violations)


def test_invalid_requests():
    r = request()
    dup = r.model_copy(deep=True)
    dup.evidence[2].id = dup.evidence[1].id
    with pytest.raises(InvalidRequestError, match="Invalid"):
        investigate(dup, FakeLLM())
    no_alert = r.model_copy(update={"evidence": [e for e in r.evidence if e.type.value != "ALERT"]})
    with pytest.raises(InvalidRequestError) as ei:
        investigate(no_alert, FakeLLM())
    assert any("ALERT" in v for v in ei.value.violations)
    inverted = r.model_copy(update={"window": r.window.model_copy(update={"start": r.window.end, "end": r.window.start})})
    with pytest.raises(InvalidRequestError):
        investigate(inverted, FakeLLM())


def test_scenarios_are_valid_distinct_and_do_not_leak_answers():
    from incident_graph.validation import validate_request
    scenarios = load_scenarios(SCEN)
    assert {s.name for s in scenarios} == {"camunda-job-backlog", "executor-capacity-regression",
                                           "database-saturation", "retry-storm", "insufficient-evidence",
                                           "surge-exhausts-db"}
    assert len({s.expected.expected_root_cause for s in scenarios}) == 5
    for s in scenarios:
        validate_request(s.request)
        ids = {e.id for e in s.request.evidence}
        assert set(s.expected.required_evidence) <= ids
        assert s.expected.expected_root_cause.value not in " ".join(e.content for e in s.request.evidence)


def test_scoring_rubric():
    s = load_scenarios(SCEN)[0]
    assert s.name == "camunda-job-backlog"
    good = score(good_assessment(), s.request, s.expected)
    assert good["rootCause"] == 1.0 and good["citationAccuracy"] == 1.0 and good["overall"] > 0.9
    trap = good_assessment(confidence=0.9, hypotheses=[Hypothesis(
        category=CauseCategory.EXECUTOR_CONFIG_REGRESSION, description="deploy", confidence=0.9,
        supporting_evidence_ids=["E-DEP-1"])])
    t = score(trap, s.request, s.expected)
    assert t["rootCause"] == 0.0 and t["uncertaintyCalibration"] == 0.0 and t["overall"] < 0.6
    assert score(None, s.request, s.expected)["overall"] == 0.0


def _scenario(name):
    return next(x for x in load_scenarios(SCEN) if x.name == name)


def test_fact_discipline_and_concision_penalise_sloppy_answers():
    s = _scenario("camunda-job-backlog")
    sloppy = good_assessment(
        findings=[Finding(claim="DB is fine, ruling out database contention", kind=ClaimKind.FACT, evidence_ids=["E-MET-4"]),
                  Finding(claim="Pool was saturated indicating a bottleneck", kind=ClaimKind.FACT, evidence_ids=["E-MET-3"]),
                  Finding(claim="Starts rose to 400/min", kind=ClaimKind.FACT, evidence_ids=["E-MET-1"])],
        unknowns=[f"q{i}" for i in range(9)],
        recommendations=[Recommendation(action=f"a{i}", rationale="r", read_only=True) for i in range(8)])
    r = score(sloppy, s.request, s.expected)
    assert abs(r["factDiscipline"] - 1 / 3) < 1e-9
    assert abs(r["concision"] - 0.3) < 1e-9
    assert score(good_assessment(), s.request, s.expected)["factDiscipline"] == 1.0


def test_undetermined_scenario_rewards_low_confidence_and_punishes_a_guess():
    s = _scenario("insufficient-evidence")
    honest = good_assessment(confidence=0.3, hypotheses=[
        Hypothesis(category=CauseCategory.UNDETERMINED, description="not enough evidence", confidence=0.3,
                   supporting_evidence_ids=["E-MET-1"]),
        Hypothesis(category=CauseCategory.LOAD_SURGE, description="possible", confidence=0.2,
                   contradicting_evidence_ids=["E-LOG-1"])],
        findings=[Finding(claim="Pending jobs 6,150 at 14:32", kind=ClaimKind.FACT, evidence_ids=["E-MET-1", "E-ALERT-1"])])
    h = score(honest, s.request, s.expected)
    assert h["rootCause"] == 1.0 and h["uncertaintyCalibration"] == 1.0
    guess = good_assessment(confidence=0.85)  # confidently says LOAD_SURGE
    g = score(guess, s.request, s.expected)
    assert g["rootCause"] == 0.0 and g["uncertaintyCalibration"] == 0.0


def test_must_mention_rewards_naming_the_amplifier():
    s = _scenario("surge-exhausts-db")
    both = good_assessment(hypotheses=[
        Hypothesis(category=CauseCategory.LOAD_SURGE, description="trigger", confidence=0.7,
                   supporting_evidence_ids=["E-LOG-1", "E-MET-1", "E-MET-3"], contradicting_evidence_ids=["E-DEP-1"]),
        Hypothesis(category=CauseCategory.DATABASE_SATURATION, description="amplifier", confidence=0.6,
                   supporting_evidence_ids=["E-MET-3"])])
    only = good_assessment(hypotheses=[
        Hypothesis(category=CauseCategory.LOAD_SURGE, description="trigger", confidence=0.7,
                   supporting_evidence_ids=["E-LOG-1", "E-MET-1", "E-MET-3"], contradicting_evidence_ids=["E-DEP-1"]),
        Hypothesis(category=CauseCategory.RETRY_STORM, description="other", confidence=0.1)])
    assert score(both, s.request, s.expected)["alternatives"] == 1.0
    assert score(only, s.request, s.expected)["alternatives"] == 0.5


def test_langsmith_eval_module_imports():
    import incident_graph.langsmith_eval as m
    assert callable(m.run_eval)
