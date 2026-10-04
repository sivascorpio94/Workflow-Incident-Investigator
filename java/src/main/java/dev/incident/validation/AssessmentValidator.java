package dev.incident.validation;

import dev.incident.domain.AnalystReport;
import dev.incident.domain.ClaimKind;
import dev.incident.domain.EvidenceItem;
import dev.incident.domain.Finding;
import dev.incident.domain.Hypothesis;
import dev.incident.domain.IncidentAssessment;
import dev.incident.domain.InvestigationRequest;
import dev.incident.domain.Recommendation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Deterministic guard on model output: citations must exist and the read-only invariant must hold. */
@Component
public class AssessmentValidator {

    /** Phrases that claim an operational change was carried out. Recommending one is fine; claiming it is not. */
    private static final Pattern REMEDIATION_CLAIM = Pattern.compile(
            "\\b(i|we|assistant)\\s+(have\\s+|has\\s+|already\\s+)?(restarted|rolled\\s+back|scaled|throttled|killed|"
                    + "reconfigured|paused|resumed|deleted|changed|increased|decreased)\\b"
                    + "|\\b(has|have)\\s+been\\s+(restarted|rolled\\s+back|scaled|throttled|reconfigured|paused)\\b"
                    + "|\\bremediation\\s+(was|has\\s+been)\\s+(executed|performed|applied)\\b",
            Pattern.CASE_INSENSITIVE);

    public void validate(IncidentAssessment a, InvestigationRequest request) {
        Set<String> known = request.evidence().stream().map(EvidenceItem::id).collect(Collectors.toSet());
        List<String> problems = new ArrayList<>();

        if (a == null) {
            throw new AssessmentValidationException(List.of("assessment is missing"));
        }
        if (a.remediationExecuted()) {
            problems.add("remediationExecuted must be false: this assistant is read-only");
        }
        if (a.summary() == null || a.summary().isBlank()) {
            problems.add("summary is blank");
        } else {
            checkNoRemediationClaim("summary", a.summary(), problems);
        }
        if (a.severity() == null) {
            problems.add("severity is missing");
        }
        checkConfidence("confidence", a.confidence(), problems);
        if (a.hypotheses().isEmpty()) {
            problems.add("at least one hypothesis is required");
        }

        for (Finding f : a.findings()) {
            checkCitations("finding", f.evidenceIds(), known, problems);
            if (f.kind() == null) {
                problems.add("finding without kind: " + f.claim());
            } else if (f.kind() == ClaimKind.FACT && f.evidenceIds().isEmpty()) {
                problems.add("FACT finding cites no evidence: " + f.claim());
            }
            checkNoRemediationClaim("finding", f.claim(), problems);
        }
        for (Hypothesis h : a.hypotheses()) {
            if (h.category() == null) {
                problems.add("hypothesis without category: " + h.description());
            }
            checkConfidence("hypothesis confidence", h.confidence(), problems);
            checkCitations("hypothesis supporting", h.supportingEvidenceIds(), known, problems);
            checkCitations("hypothesis contradicting", h.contradictingEvidenceIds(), known, problems);
        }
        for (Recommendation r : a.recommendations()) {
            if (!r.readOnly()) {
                problems.add("recommendation is not read-only: " + r.action());
            }
            checkNoRemediationClaim("recommendation", r.action(), problems);
        }
        if (!problems.isEmpty()) {
            throw new AssessmentValidationException(problems);
        }
    }

    /** An analyst may only claim to have reviewed evidence that was actually submitted. */
    public void validateAnalyst(String analyst, AnalystReport r, InvestigationRequest request) {
        Set<String> known = request.evidence().stream().map(EvidenceItem::id).collect(Collectors.toSet());
        List<String> problems = new ArrayList<>();
        if (r == null) {
            throw new AssessmentValidationException(List.of(analyst + " report is missing"));
        }
        checkCitations(analyst + " reviewed", r.reviewedEvidenceIds(), known, problems);
        for (Finding f : r.findings()) {
            checkCitations(analyst + " finding", f.evidenceIds(), known, problems);
        }
        if (!problems.isEmpty()) {
            throw new AssessmentValidationException(problems);
        }
    }

    private static void checkCitations(String where, List<String> ids, Set<String> known, List<String> problems) {
        for (String id : ids) {
            if (!known.contains(id)) {
                problems.add(where + " cites unknown evidence id: " + id);
            }
        }
    }

    private static void checkConfidence(String where, double c, List<String> problems) {
        if (Double.isNaN(c) || c < 0.0 || c > 1.0) {
            problems.add(where + " must be within [0,1] but was " + c);
        }
    }

    private static void checkNoRemediationClaim(String where, String text, List<String> problems) {
        if (text != null && REMEDIATION_CLAIM.matcher(text).find()) {
            problems.add(where + " appears to claim remediation was carried out: " + text);
        }
    }
}
