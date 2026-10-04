"""Deterministic checks - same rules as the Java RequestValidator / AssessmentValidator."""
from __future__ import annotations

import math
import re

from .errors import AssessmentValidationError, InvalidRequestError
from .models import (AnalystReport, ClaimKind, EvidenceType, IncidentAssessment, InvestigationRequest)

_REMEDIATION_CLAIM = re.compile(
    r"\b(i|we|assistant)\s+(have\s+|has\s+|already\s+)?(restarted|rolled\s+back|scaled|throttled|killed|"
    r"reconfigured|paused|resumed|deleted|changed|increased|decreased)\b"
    r"|\b(has|have)\s+been\s+(restarted|rolled\s+back|scaled|throttled|reconfigured|paused)\b"
    r"|\bremediation\s+(was|has\s+been)\s+(executed|performed|applied)\b",
    re.IGNORECASE,
)


def validate_request(req: InvestigationRequest) -> None:
    problems: list[str] = []
    w = req.window
    if not w.start < w.end:
        problems.append("window.start must be before window.end")
    seen: set[str] = set()
    for e in req.evidence:
        if e.id in seen:
            problems.append(f"duplicate evidence id: {e.id}")
        seen.add(e.id)
        if e.type == EvidenceType.RUNBOOK:
            continue
        if e.type == EvidenceType.DEPLOYMENT:
            if e.timestamp > w.end:
                problems.append(f"deployment {e.id} is after window.end")
        elif e.timestamp < w.start or e.timestamp > w.end:
            problems.append(f"{e.type.value} evidence {e.id} is outside the investigation window")
    if not any(e.type == EvidenceType.ALERT for e in req.evidence):
        problems.append("at least one ALERT evidence item is required")
    if problems:
        raise InvalidRequestError("Invalid investigation request", problems)


def _cite(where: str, ids: list[str], known: set[str], problems: list[str]) -> None:
    problems.extend(f"{where} cites unknown evidence id: {i}" for i in ids if i not in known)


def _no_claim(where: str, text: str | None, problems: list[str]) -> None:
    if text and _REMEDIATION_CLAIM.search(text):
        problems.append(f"{where} appears to claim remediation was carried out: {text}")


def _conf(where: str, c: float, problems: list[str]) -> None:
    if math.isnan(c) or c < 0 or c > 1:
        problems.append(f"{where} must be within [0,1] but was {c}")


def validate_analyst(name: str, report: AnalystReport, req: InvestigationRequest) -> None:
    known = {e.id for e in req.evidence}
    problems: list[str] = []
    _cite(f"{name} reviewed", report.reviewed_evidence_ids, known, problems)
    for f in report.findings:
        _cite(f"{name} finding", f.evidence_ids, known, problems)
    if problems:
        raise AssessmentValidationError("Model output failed validation", problems)


def validate_assessment(a: IncidentAssessment, req: InvestigationRequest) -> None:
    known = {e.id for e in req.evidence}
    problems: list[str] = []
    if a.remediation_executed:
        problems.append("remediationExecuted must be false: this assistant is read-only")
    if not a.summary.strip():
        problems.append("summary is blank")
    else:
        _no_claim("summary", a.summary, problems)
    _conf("confidence", a.confidence, problems)
    if not a.hypotheses:
        problems.append("at least one hypothesis is required")
    for f in a.findings:
        _cite("finding", f.evidence_ids, known, problems)
        if f.kind == ClaimKind.FACT and not f.evidence_ids:
            problems.append(f"FACT finding cites no evidence: {f.claim}")
        _no_claim("finding", f.claim, problems)
    for h in a.hypotheses:
        _conf("hypothesis confidence", h.confidence, problems)
        _cite("hypothesis supporting", h.supporting_evidence_ids, known, problems)
        _cite("hypothesis contradicting", h.contradicting_evidence_ids, known, problems)
    for r in a.recommendations:
        if not r.read_only:
            problems.append(f"recommendation is not read-only: {r.action}")
        _no_claim("recommendation", r.action, problems)
    if problems:
        raise AssessmentValidationError("Model output failed validation", problems)
