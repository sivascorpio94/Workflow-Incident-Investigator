package dev.incident.eval;

import dev.incident.domain.CauseCategory;
import java.util.List;

/** Ground truth for one scenario. Loaded from incident.yaml; never sent to the model. */
public record ExpectedAnswer(String incidentId, CauseCategory expectedRootCause,
                             List<String> requiredEvidence, List<CauseCategory> mustNotConcludeAsPrimary, String notes) {}
