package dev.incident.validation;

import java.util.List;

public class ModelFailureException extends InvestigationException {
    public ModelFailureException(String step, Throwable cause) {
        super("Model call failed in step '" + step + "'", List.of(String.valueOf(cause.getMessage())), cause);
    }
}
