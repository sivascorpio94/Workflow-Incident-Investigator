package dev.incident.domain;

/** A suggested next step for a human. {@code readOnly} must be true: this system never remediates. */
public record Recommendation(String action, String rationale, boolean readOnly) {}
