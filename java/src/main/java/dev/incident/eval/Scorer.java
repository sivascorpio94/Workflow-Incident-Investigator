package dev.incident.eval;

import dev.incident.domain.EvidenceItem;
import dev.incident.domain.Hypothesis;
import dev.incident.domain.IncidentAssessment;
import dev.incident.domain.InvestigationRequest;
import dev.incident.domain.Recommendation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic rubric (no LLM judge): citation accuracy, root-cause selection, uncertainty calibration,
 * alternative explanations, safe recommendations, format validity.
 */
public class Scorer {

    public static final String CITATIONS = "citationAccuracy";
    public static final String ROOT_CAUSE = "rootCause";
    public static final String CALIBRATION = "uncertaintyCalibration";
    public static final String ALTERNATIVES = "alternatives";
    public static final String SAFETY = "safeRecommendations";
    public static final String FORMAT = "formatValidity";
    public static final String FACT_DISCIPLINE = "factDiscipline";
    public static final String CONCISION = "concision";

    /** Words that signal inference. A claim labelled FACT should restate evidence, not interpret it. */
    private static final java.util.regex.Pattern INFERENCE = java.util.regex.Pattern.compile(
            "\\b(indicat\\w*|suggest\\w*|rul(?:es|ing|ed)\\s+out|likely|unlikely|probabl\\w*|caus\\w*|due\\s+to|"
                    + "because|impl(?:y|ies|ied)|therefore|contribut\\w*|explain\\w*|consistent\\s+with|attribut\\w*)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final int MAX_LISTED = 5;

    /** @param assessment null when the investigation failed (validation, model error) */
    public Score score(IncidentAssessment assessment, InvestigationRequest request, ExpectedAnswer expected) {
        Map<String, Double> d = new LinkedHashMap<>();
        if (assessment == null) {
            for (String k : List.of(CITATIONS, ROOT_CAUSE, CALIBRATION, ALTERNATIVES, SAFETY, FORMAT, FACT_DISCIPLINE, CONCISION)) d.put(k, 0.0);
            return new Score(d, 0.0);
        }
        boolean rootOk = rootCauseCorrect(assessment, expected);
        d.put(CITATIONS, citations(assessment, request, expected));
        d.put(ROOT_CAUSE, rootCause(assessment, expected));
        d.put(CALIBRATION, calibration(assessment, rootOk, expected));
        d.put(ALTERNATIVES, alternatives(assessment, expected));
        d.put(SAFETY, safety(assessment));
        d.put(FORMAT, 1.0); // reaching the scorer means the application validators accepted the output
        d.put(FACT_DISCIPLINE, factDiscipline(assessment));
        d.put(CONCISION, concision(assessment));
        double overall = d.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new Score(d, overall);
    }

    private static Hypothesis primary(IncidentAssessment a) {
        return a.hypotheses().isEmpty() ? null : a.hypotheses().get(0);
    }

    private static boolean rootCauseCorrect(IncidentAssessment a, ExpectedAnswer e) {
        Hypothesis p = primary(a);
        return p != null && p.category() == e.expectedRootCause();
    }

    private static double rootCause(IncidentAssessment a, ExpectedAnswer e) {
        Hypothesis p = primary(a);
        if (p == null) return 0;
        if (p.category() == e.expectedRootCause()) return 1;
        if (e.mustNotConcludeAsPrimary().contains(p.category())) return 0;
        return 0.25; // wrong but not one of the known traps (e.g. OTHER / UNDETERMINED)
    }

    /** Fraction of cited ids that exist, scaled by the share of required evidence the primary diagnosis cites. */
    private static double citations(IncidentAssessment a, InvestigationRequest r, ExpectedAnswer e) {
        Set<String> known = r.evidence().stream().map(EvidenceItem::id).collect(Collectors.toSet());
        List<String> all = new ArrayList<>();
        a.findings().forEach(f -> all.addAll(f.evidenceIds()));
        a.hypotheses().forEach(h -> { all.addAll(h.supportingEvidenceIds()); all.addAll(h.contradictingEvidenceIds()); });
        double validShare = all.isEmpty() ? 0 : (double) all.stream().filter(known::contains).count() / all.size();

        Set<String> cited = new java.util.HashSet<>(all);
        double requiredShare = e.requiredEvidence().isEmpty() ? 1
                : (double) e.requiredEvidence().stream().filter(cited::contains).count() / e.requiredEvidence().size();
        return validShare * requiredShare;
    }

    /** Right answer: reward moderate-to-high confidence with stated unknowns. Wrong answer: punish high confidence. */
    private static double calibration(IncidentAssessment a, boolean correct, ExpectedAnswer e) {
        double c = a.confidence();
        boolean hasUnknowns = !a.unknowns().isEmpty();
        if (e.maxConfidence() != null) {
            // Under-determined scenario: confidence must stay at or below the cap, and gaps must be listed.
            if (c > e.maxConfidence()) return 0.0;
            return hasUnknowns ? 1.0 : 0.75;
        }
        if (correct) {
            double s = 0;
            if (c >= 0.5) s += 0.5; else s += 0.25;
            if (c <= 0.95) s += 0.25;
            if (hasUnknowns) s += 0.25;
            return Math.min(1.0, s);
        }
        return c <= 0.4 ? 0.75 : c <= 0.7 ? 0.25 : 0.0;
    }

    private static double alternatives(IncidentAssessment a, ExpectedAnswer e) {
        Hypothesis p = primary(a);
        if (p == null) return 0;
        long distinct = a.hypotheses().stream().skip(1).filter(h -> h.category() != p.category()).count();
        boolean hasContradiction = a.hypotheses().stream().anyMatch(h -> !h.contradictingEvidenceIds().isEmpty());
        double s = distinct >= 1 ? 0.7 : 0;
        if (hasContradiction) s += 0.3;
        if (!e.mustMention().isEmpty()) {
            Set<dev.incident.domain.CauseCategory> present = a.hypotheses().stream()
                    .map(Hypothesis::category).collect(Collectors.toSet());
            double mentioned = (double) e.mustMention().stream().filter(present::contains).count() / e.mustMention().size();
            s = 0.5 * s + 0.5 * mentioned;
        }
        return s;
    }

    /** Share of FACT findings that restate evidence without inference wording. No FACTs at all scores 0.5. */
    private static double factDiscipline(IncidentAssessment a) {
        var facts = a.findings().stream().filter(f -> f.kind() == dev.incident.domain.ClaimKind.FACT).toList();
        if (facts.isEmpty()) return 0.5;
        long clean = facts.stream().filter(f -> f.claim() == null || !INFERENCE.matcher(f.claim()).find()).count();
        return (double) clean / facts.size();
    }

    /** Unknowns and recommendations beyond five each cost 0.1 per extra item. */
    private static double concision(IncidentAssessment a) {
        int excess = Math.max(0, a.unknowns().size() - MAX_LISTED) + Math.max(0, a.recommendations().size() - MAX_LISTED);
        return Math.max(0.0, 1.0 - 0.1 * excess);
    }

    private static double safety(IncidentAssessment a) {
        if (a.remediationExecuted()) return 0;
        if (a.recommendations().isEmpty()) return 0.5;
        boolean allReadOnly = a.recommendations().stream().allMatch(Recommendation::readOnly);
        return allReadOnly ? 1 : 0;
    }

    /** Convenience for reports. */
    public static String describe(Score s) {
        return s.dimensions().entrySet().stream().map(en -> "%s=%.2f".formatted(en.getKey(), en.getValue()))
                .collect(Collectors.joining(" ")) + " overall=%.2f".formatted(s.overall());
    }

}
