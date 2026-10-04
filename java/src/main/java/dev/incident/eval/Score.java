package dev.incident.eval;

import java.util.Map;

/** Each dimension is in [0,1]; overall is their unweighted mean. */
public record Score(Map<String, Double> dimensions, double overall) {}
