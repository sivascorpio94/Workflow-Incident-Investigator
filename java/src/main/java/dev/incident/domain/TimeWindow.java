package dev.incident.domain;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record TimeWindow(@NotNull Instant start, @NotNull Instant end) {}
