package dev.incident.eval;

import dev.incident.domain.InvestigationRequest;

public record Scenario(String name, InvestigationRequest request, ExpectedAnswer expected) {}
