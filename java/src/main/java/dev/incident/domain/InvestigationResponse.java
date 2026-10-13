package dev.incident.domain;

import dev.incident.observability.InvestigationTrace;

public record InvestigationResponse(IncidentAssessment assessment, InvestigationTrace trace) {}
