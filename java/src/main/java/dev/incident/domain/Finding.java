package dev.incident.domain;

import java.util.List;

/** A single claim, labelled as fact or hypothesis, with the evidence behind it. */
public record Finding(String claim, ClaimKind kind, List<String> evidenceIds) {
    public Finding {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
