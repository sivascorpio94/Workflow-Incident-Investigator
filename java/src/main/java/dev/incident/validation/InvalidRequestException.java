package dev.incident.validation;

import java.util.List;

public class InvalidRequestException extends InvestigationException {
    public InvalidRequestException(List<String> violations) {
        super("Invalid investigation request", violations, null);
    }
}
