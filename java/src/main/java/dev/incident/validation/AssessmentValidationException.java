package dev.incident.validation;

import java.util.List;

public class AssessmentValidationException extends InvestigationException {
    public AssessmentValidationException(List<String> violations) {
        super("Model output failed validation", violations, null);
    }
}
