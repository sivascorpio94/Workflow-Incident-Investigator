package dev.incident.eval;

import dev.incident.domain.CauseCategory;
import java.util.List;

/**
 * Ground truth for one scenario. Loaded from incident.yaml; never sent to the model.
 *
 * @param maxConfidence if non-null, overall confidence above this is penalised (for under-determined scenarios)
 * @param mustMention   categories that must appear among the hypotheses even if not primary (e.g. an amplifier)
 */
public record ExpectedAnswer(String incidentId, CauseCategory expectedRootCause,
                             List<String> requiredEvidence, List<CauseCategory> mustNotConcludeAsPrimary, String notes,
                             Double maxConfidence, List<CauseCategory> mustMention) {
    public ExpectedAnswer {
        mustMention = mustMention == null ? List.of() : List.copyOf(mustMention);
    }

    public ExpectedAnswer(String incidentId, CauseCategory expectedRootCause, List<String> requiredEvidence,
                          List<CauseCategory> mustNotConcludeAsPrimary, String notes) {
        this(incidentId, expectedRootCause, requiredEvidence, mustNotConcludeAsPrimary, notes, null, List.of());
    }
}
