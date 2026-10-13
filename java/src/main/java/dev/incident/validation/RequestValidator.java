package dev.incident.validation;

import dev.incident.domain.EvidenceItem;
import dev.incident.domain.EvidenceType;
import dev.incident.domain.InvestigationRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Semantic checks that bean validation cannot express. Field presence is handled by @Valid. */
@Component
public class RequestValidator {

    public void validate(InvestigationRequest request) {
        List<String> problems = new ArrayList<>();
        var window = request.window();
        if (!window.start().isBefore(window.end())) {
            problems.add("window.start must be before window.end");
        }

        Set<String> seen = new HashSet<>();
        for (EvidenceItem e : request.evidence()) {
            if (!seen.add(e.id())) {
                problems.add("duplicate evidence id: " + e.id());
            }
            checkTimestamp(e, request, problems);
        }
        if (request.evidence().stream().noneMatch(e -> e.type() == EvidenceType.ALERT)) {
            problems.add("at least one ALERT evidence item is required");
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
    }

    private void checkTimestamp(EvidenceItem e, InvestigationRequest request, List<String> problems) {
        var w = request.window();
        switch (e.type()) {
            case RUNBOOK -> { }
            case DEPLOYMENT -> {
                if (e.timestamp().isAfter(w.end())) {
                    problems.add("deployment " + e.id() + " is after window.end");
                }
            }
            default -> {
                if (e.timestamp().isBefore(w.start()) || e.timestamp().isAfter(w.end())) {
                    problems.add(e.type() + " evidence " + e.id() + " is outside the investigation window");
                }
            }
        }
    }
}
