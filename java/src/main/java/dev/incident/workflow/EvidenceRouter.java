package dev.incident.workflow;

import dev.incident.domain.EvidenceItem;
import dev.incident.domain.EvidenceType;
import dev.incident.domain.InvestigationRequest;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Decides which analyst sees which evidence. Alerts go to everyone for shared context. */
@Component
public class EvidenceRouter {
    private static final Set<EvidenceType> RUNTIME = Set.of(EvidenceType.ALERT, EvidenceType.METRIC, EvidenceType.LOG);
    private static final Set<EvidenceType> CHANGE = Set.of(EvidenceType.ALERT, EvidenceType.DEPLOYMENT, EvidenceType.RUNBOOK);

    public List<EvidenceItem> forRuntimeAnalyst(InvestigationRequest r) { return filter(r, RUNTIME); }
    public List<EvidenceItem> forChangeAnalyst(InvestigationRequest r) { return filter(r, CHANGE); }
    public List<EvidenceItem> forTriage(InvestigationRequest r) { return filter(r, Set.of(EvidenceType.ALERT)); }

    private static List<EvidenceItem> filter(InvestigationRequest r, Set<EvidenceType> types) {
        return r.evidence().stream().filter(e -> types.contains(e.type())).toList();
    }
}
