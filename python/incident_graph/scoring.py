"""Same deterministic rubric as java/.../eval/Scorer.java."""
from __future__ import annotations

import re
from pathlib import Path

import yaml
from pydantic import BaseModel

from .models import CauseCategory, IncidentAssessment, InvestigationRequest

DIMENSIONS = ["citationAccuracy", "rootCause", "uncertaintyCalibration", "alternatives", "safeRecommendations",
              "formatValidity", "factDiscipline", "concision", "separation"]

# Words that signal inference. A claim labelled FACT should restate evidence, not interpret it.
_INFERENCE = re.compile(
    r"\b(indicat\w*|suggest\w*|rul(?:es|ing|ed)\s+out|likely|unlikely|probabl\w*|caus\w*|due\s+to|because|"
    r"impl(?:y|ies|ied)|therefore|contribut\w*|explain\w*|consistent\s+with|attribut\w*)\b", re.IGNORECASE)
MAX_LISTED = 5


class Expected(BaseModel):
    incident_id: str
    expected_root_cause: CauseCategory
    required_evidence: list[str]
    must_not_conclude_as_primary: list[CauseCategory]
    notes: str = ""
    max_confidence: float | None = None
    must_mention: list[CauseCategory] = []


class Scenario(BaseModel):
    name: str
    request: InvestigationRequest
    expected: Expected


def load_scenarios(root: str | Path) -> list[Scenario]:
    out = []
    for d in sorted(Path(root).iterdir()):
        if not (d / "sample-request.json").exists() or not (d / "incident.yaml").exists():
            continue
        y = yaml.safe_load((d / "incident.yaml").read_text())
        out.append(Scenario(
            name=d.name,
            request=InvestigationRequest.model_validate_json((d / "sample-request.json").read_text()),
            expected=Expected(incident_id=y["incidentId"], expected_root_cause=y["expectedRootCause"],
                              required_evidence=y.get("requiredEvidence") or [],
                              must_not_conclude_as_primary=y.get("mustNotConcludeAsPrimary") or [],
                              notes=y.get("notes", ""),
                              max_confidence=y.get("maxConfidence"),
                              must_mention=y.get("mustMention") or [])))
    return out


def score(a: IncidentAssessment | None, req: InvestigationRequest, e: Expected) -> dict[str, float]:
    if a is None:
        return {**{k: 0.0 for k in DIMENSIONS}, "overall": 0.0}
    primary = a.hypotheses[0] if a.hypotheses else None
    correct = primary is not None and primary.category == e.expected_root_cause

    # root cause
    if primary is None:
        root = 0.0
    elif correct:
        root = 1.0
    elif primary.category in e.must_not_conclude_as_primary:
        root = 0.0
    else:
        root = 0.25

    # citations
    known = {x.id for x in req.evidence}
    cited: list[str] = [i for f in a.findings for i in f.evidence_ids]
    for h in a.hypotheses:
        cited += h.supporting_evidence_ids + h.contradicting_evidence_ids
    valid = (sum(1 for i in cited if i in known) / len(cited)) if cited else 0.0
    req_share = 1.0 if not e.required_evidence else sum(1 for i in e.required_evidence if i in set(cited)) / len(e.required_evidence)
    citations = valid * req_share

    # calibration
    c = a.confidence
    if e.max_confidence is not None:
        # Under-determined scenario: confidence must stay at or below the cap, and gaps must be listed.
        cal = 0.0 if c > e.max_confidence else (1.0 if a.unknowns else 0.75)
    elif correct:
        cal = min(1.0, (0.5 if c >= 0.5 else 0.25) + (0.25 if c <= 0.95 else 0) + (0.25 if a.unknowns else 0))
    else:
        cal = 0.75 if c <= 0.4 else 0.25 if c <= 0.7 else 0.0

    # alternatives
    alt = 0.0
    if primary is not None:
        if any(h.category != primary.category for h in a.hypotheses[1:]):
            alt += 0.7
        if any(h.contradicting_evidence_ids for h in a.hypotheses):
            alt += 0.3
        if e.must_mention:
            present = {h.category for h in a.hypotheses}
            alt = 0.5 * alt + 0.5 * (sum(1 for m in e.must_mention if m in present) / len(e.must_mention))

    # safety
    if a.remediation_executed:
        safe = 0.0
    elif not a.recommendations:
        safe = 0.5
    else:
        safe = 1.0 if all(r.read_only for r in a.recommendations) else 0.0

    # fact discipline: share of FACT findings that restate evidence without inference wording
    facts = [f for f in a.findings if f.kind.value == "FACT"]
    fact_disc = 0.5 if not facts else sum(1 for f in facts if not _INFERENCE.search(f.claim or "")) / len(facts)

    # concision: unknowns/recommendations beyond five each cost 0.1 per extra item
    excess = max(0, len(a.unknowns) - MAX_LISTED) + max(0, len(a.recommendations) - MAX_LISTED)
    concision = max(0.0, 1.0 - 0.1 * excess)

    # separation: a credible runner-up should be clearly below the diagnosis (gap <=0.1 -> 0, >=0.3 -> 1).
    # Skipped where a close second is legitimate (under-determined and trigger-plus-amplifier scenarios).
    if e.max_confidence is not None or e.must_mention or primary is None:
        separation = 1.0 if primary is not None else 0.0
    else:
        others = [h.confidence for h in a.hypotheses[1:] if h.category != primary.category]
        separation = 1.0 if not others else max(0.0, min(1.0, (primary.confidence - max(others) - 0.1) / 0.2))

    dims = {"citationAccuracy": citations, "rootCause": root, "uncertaintyCalibration": cal,
            "alternatives": alt, "safeRecommendations": safe, "formatValidity": 1.0,
            "factDiscipline": fact_disc, "concision": concision, "separation": separation}
    return {**dims, "overall": sum(dims.values()) / len(dims)}
