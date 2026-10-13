package dev.incident.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record EvidenceItem(
        @NotBlank String id,
        @NotNull EvidenceType type,
        @NotBlank String source,
        @NotNull Instant timestamp,
        @NotBlank String content) {}
