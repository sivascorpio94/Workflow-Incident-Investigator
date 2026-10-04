package dev.incident.api;

import dev.incident.validation.AssessmentValidationException;
import dev.incident.validation.InvalidRequestException;
import dev.incident.validation.InvestigationException;
import dev.incident.validation.ModelFailureException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvestigationException.class)
    ResponseEntity<ProblemDetail> investigation(InvestigationException e) {
        HttpStatus status = switch (e) {
            case InvalidRequestException x -> HttpStatus.BAD_REQUEST;
            case AssessmentValidationException x -> HttpStatus.UNPROCESSABLE_CONTENT;
            case ModelFailureException x -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        // Violations are our own validation messages; provider exception text is deliberately not echoed.
        List<String> violations = e instanceof ModelFailureException ? List.of() : e.violations();
        return problem(status, e.getMessage(), e.investigationId(), violations);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> beanValidation(MethodArgumentNotValidException e) {
        List<String> v = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).toList();
        return problem(HttpStatus.BAD_REQUEST, "Invalid investigation request", null, v);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed JSON request body", null, List.of());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String investigationId,
                                                         List<String> violations) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, title);
        pd.setProperty("investigationId", investigationId);
        pd.setProperty("violations", violations);
        return ResponseEntity.status(status).body(pd);
    }
}
