"""Typed contract. Field names are camelCase on the wire to match contracts/investigation-contract.md."""
from __future__ import annotations

from datetime import datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class Wire(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class EvidenceType(str, Enum):
    ALERT = "ALERT"
    METRIC = "METRIC"
    LOG = "LOG"
    DEPLOYMENT = "DEPLOYMENT"
    RUNBOOK = "RUNBOOK"


class Severity(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class ClaimKind(str, Enum):
    FACT = "FACT"
    HYPOTHESIS = "HYPOTHESIS"


class CauseCategory(str, Enum):
    LOAD_SURGE = "LOAD_SURGE"
    EXECUTOR_CONFIG_REGRESSION = "EXECUTOR_CONFIG_REGRESSION"
    DATABASE_SATURATION = "DATABASE_SATURATION"
    RETRY_STORM = "RETRY_STORM"
    OTHER = "OTHER"
    UNDETERMINED = "UNDETERMINED"


class TimeWindow(Wire):
    start: datetime
    end: datetime


class EvidenceItem(Wire):
    id: str = Field(min_length=1)
    type: EvidenceType
    source: str = Field(min_length=1)
    timestamp: datetime
    content: str = Field(min_length=1)


class InvestigationRequest(Wire):
    incident_id: str = Field(min_length=1)
    title: str = Field(min_length=1)
    window: TimeWindow
    evidence: list[EvidenceItem] = Field(min_length=1)


class Finding(Wire):
    claim: str
    kind: ClaimKind
    evidence_ids: list[str] = Field(default_factory=list)


class Hypothesis(Wire):
    category: CauseCategory
    description: str
    confidence: float
    supporting_evidence_ids: list[str] = Field(default_factory=list)
    contradicting_evidence_ids: list[str] = Field(default_factory=list)


class Recommendation(Wire):
    action: str
    rationale: str
    read_only: bool


class TriageResult(Wire):
    """Alert facts only - triage must not diagnose a cause."""
    alert_summary: str
    affected_system: str
    observed_symptoms: list[str] = Field(default_factory=list)
    alert_evidence_ids: list[str] = Field(default_factory=list)


class AnalystReport(Wire):
    reviewed_evidence_ids: list[str] = Field(default_factory=list)
    findings: list[Finding] = Field(default_factory=list)
    open_questions: list[str] = Field(default_factory=list)


class IncidentAssessment(Wire):
    incident_id: str
    summary: str
    severity: Severity
    findings: list[Finding] = Field(default_factory=list)
    hypotheses: list[Hypothesis] = Field(default_factory=list)
    unknowns: list[str] = Field(default_factory=list)
    recommendations: list[Recommendation] = Field(default_factory=list)
    confidence: float
    remediation_executed: bool


class StepTrace(Wire):
    name: str
    duration_ms: int
    outcome: str  # OK | FAILED
    error: str | None = None
    prompt_tokens: int | None = None
    completion_tokens: int | None = None


class InvestigationResponse(Wire):
    assessment: IncidentAssessment
    steps: list[StepTrace]
