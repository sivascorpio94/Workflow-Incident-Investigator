package dev.incident;

import static org.assertj.core.api.Assertions.assertThat;

import dev.incident.domain.EvidenceItem;
import dev.incident.workflow.EvidenceRouter;
import org.junit.jupiter.api.Test;

class EvidenceRouterTest {
    private final EvidenceRouter router = new EvidenceRouter();

    private static java.util.List<String> ids(java.util.List<EvidenceItem> l) { return l.stream().map(EvidenceItem::id).toList(); }

    @Test void runtimeAnalystSeesAlertsMetricsLogsOnly() {
        assertThat(ids(router.forRuntimeAnalyst(Fixtures.request())))
                .containsExactlyInAnyOrder("E-ALERT-1", "E-MET-1", "E-LOG-1");
    }

    @Test void changeAnalystSeesAlertsDeploymentsRunbookOnly() {
        assertThat(ids(router.forChangeAnalyst(Fixtures.request())))
                .containsExactlyInAnyOrder("E-ALERT-1", "E-DEP-1", "E-RB-1");
    }

    @Test void triageSeesAlertsOnly() {
        assertThat(ids(router.forTriage(Fixtures.request()))).containsExactly("E-ALERT-1");
    }
}
