"""LangGraph version of the workflow:

    validate_request -> triage -> (runtime_analyst || change_analyst) -> verifier -> validate_assessment

Bounded: explicit edges, typed state, no tools, no memory, no agent-to-agent chat.
"""
from __future__ import annotations

import operator
import time
from typing import Annotated, Callable, TypedDict

from langgraph.graph import END, START, StateGraph

from . import prompts
from .errors import InvestigationError, ModelFailureError
from .llm import LLMResult, StructuredLLM
from .models import (AnalystReport, EvidenceType, IncidentAssessment, InvestigationRequest,
                     InvestigationResponse, StepTrace, TriageResult)
from .validation import validate_analyst, validate_assessment, validate_request

RUNTIME_TYPES = {EvidenceType.ALERT, EvidenceType.METRIC, EvidenceType.LOG}
CHANGE_TYPES = {EvidenceType.ALERT, EvidenceType.DEPLOYMENT, EvidenceType.RUNBOOK}


class State(TypedDict, total=False):
    request: InvestigationRequest
    triage: TriageResult
    runtime: AnalystReport
    change: AnalystReport
    assessment: IncidentAssessment
    steps: Annotated[list[StepTrace], operator.add]  # reducer: parallel branches append safely


def route(req: InvestigationRequest, types: set[EvidenceType]):
    return [e for e in req.evidence if e.type in types]


def _window(req: InvestigationRequest) -> str:
    return f"{req.window.start.isoformat()} to {req.window.end.isoformat()}"


def _traced(name: str, body: Callable[[], LLMResult | None]) -> tuple[object, StepTrace]:
    start = time.perf_counter()
    try:
        result = body()
    except InvestigationError as e:
        raise _with_trace(e, name, start, str(e)) from None
    except Exception as e:  # provider/parse errors: keep the class name only, never the message
        raise _with_trace(ModelFailureError(f"Model call failed in step '{name}'"), name, start, type(e).__name__) from e
    ms = int((time.perf_counter() - start) * 1000)
    pt = getattr(result, "prompt_tokens", None)
    ct = getattr(result, "completion_tokens", None)
    return result, StepTrace(name=name, duration_ms=ms, outcome="OK", prompt_tokens=pt, completion_tokens=ct)


def _with_trace(err: InvestigationError, name: str, start: float, why: str) -> InvestigationError:
    err.failed_step = StepTrace(name=name, duration_ms=int((time.perf_counter() - start) * 1000),
                                outcome="FAILED", error=why)
    return err


def build_graph(llm: StructuredLLM):
    def validate_request_node(state: State):
        _, step = _traced("validate-request", lambda: validate_request(state["request"]))
        return {"steps": [step]}

    def triage_node(state: State):
        req = state["request"]
        res, step = _traced("triage", lambda: llm(
            "triage", prompts.TRIAGE,
            prompts.triage_user(req.incident_id, req.title, _window(req), route(req, {EvidenceType.ALERT})),
            TriageResult))
        return {"triage": res.value, "steps": [step]}

    def analyst(step_name: str, system: str, types: set[EvidenceType], key: str):
        def node(state: State):
            req = state["request"]
            res, step = _traced(step_name, lambda: _analyst_call(req, state["triage"]))
            return {key: res.value, "steps": [step]}

        def _analyst_call(req, triage):
            r = llm(step_name, system, prompts.analyst_user(req.incident_id, _window(req), triage, route(req, types)),
                    AnalystReport)
            validate_analyst(step_name, r.value, req)
            return r
        return node

    def verifier_node(state: State):
        req = state["request"]
        res, step = _traced("verifier", lambda: llm(
            "verifier", prompts.VERIFIER,
            prompts.verifier_user(req.incident_id, _window(req), state["triage"], state["runtime"], state["change"],
                                  req.evidence),
            IncidentAssessment))
        return {"assessment": res.value, "steps": [step]}

    def validate_node(state: State):
        _, step = _traced("validate", lambda: validate_assessment(state["assessment"], state["request"]))
        return {"steps": [step]}

    g = StateGraph(State)
    g.add_node("validate_request", validate_request_node)
    g.add_node("triage", triage_node)
    g.add_node("runtime_analyst", analyst("runtime-analyst", prompts.RUNTIME_ANALYST, RUNTIME_TYPES, "runtime"))
    g.add_node("change_analyst", analyst("change-analyst", prompts.CHANGE_ANALYST, CHANGE_TYPES, "change"))
    g.add_node("verifier", verifier_node)
    g.add_node("validate_assessment", validate_node)

    g.add_edge(START, "validate_request")
    g.add_edge("validate_request", "triage")
    g.add_edge("triage", "runtime_analyst")   # fan-out: both analysts run in the same superstep
    g.add_edge("triage", "change_analyst")
    g.add_edge(["runtime_analyst", "change_analyst"], "verifier")  # fan-in: wait for both
    g.add_edge("verifier", "validate_assessment")
    g.add_edge("validate_assessment", END)
    return g.compile()


def investigate(request: InvestigationRequest, llm: StructuredLLM) -> InvestigationResponse:
    """Run the graph; returns the assessment plus per-step trace. Raises InvestigationError subclasses."""
    final = build_graph(llm).invoke({"request": request, "steps": []})
    return InvestigationResponse(assessment=final["assessment"], steps=final["steps"])
