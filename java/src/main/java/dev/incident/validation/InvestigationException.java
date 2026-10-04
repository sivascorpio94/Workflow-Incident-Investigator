package dev.incident.validation;

import java.util.List;

/** Base for failures the API maps to HTTP problems; carries the investigation id for correlation. */
public abstract class InvestigationException extends RuntimeException {
    private final List<String> violations;
    private String investigationId;

    protected InvestigationException(String message, List<String> violations, Throwable cause) {
        super(message, cause);
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() { return violations; }
    public String investigationId() { return investigationId; }
    public void investigationId(String id) { this.investigationId = id; }
}
