package dev.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.incident.domain.*;
import dev.incident.validation.InvalidRequestException;
import dev.incident.validation.RequestValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestValidatorTest {
    private final RequestValidator v = new RequestValidator();

    @Test void acceptsValidRequest() {
        assertThatCode(() -> v.validate(Fixtures.request())).doesNotThrowAnyException();
    }

    @Test void rejectsMissingAlert() {
        assertThatThrownBy(() -> v.validate(Fixtures.without("E-ALERT-1")))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(e -> assertThat(((InvalidRequestException) e).violations()).anyMatch(s -> s.contains("ALERT")));
    }

    @Test void rejectsDuplicateEvidenceIds() {
        List<EvidenceItem> l = new ArrayList<>(Fixtures.request().evidence());
        l.add(Fixtures.ev("E-MET-1", EvidenceType.METRIC, "2026-03-12T10:01:00Z"));
        assertThatThrownBy(() -> v.validate(Fixtures.with(l))).isInstanceOf(InvalidRequestException.class)
                .satisfies(e -> assertThat(((InvalidRequestException) e).violations()).anyMatch(s -> s.contains("duplicate evidence id: E-MET-1")));
    }

    @Test void rejectsInvertedWindow() {
        var r = new InvestigationRequest("I", "t", new TimeWindow(Fixtures.END, Fixtures.START), Fixtures.request().evidence());
        assertThatThrownBy(() -> v.validate(r)).isInstanceOf(InvalidRequestException.class);
    }

    @Test void rejectsMetricOutsideWindowButAllowsEarlierDeploymentAndRunbook() {
        List<EvidenceItem> bad = new ArrayList<>(Fixtures.request().evidence());
        bad.add(Fixtures.ev("E-MET-9", EvidenceType.METRIC, "2026-03-12T11:00:00Z"));
        assertThatThrownBy(() -> v.validate(Fixtures.with(bad))).isInstanceOf(InvalidRequestException.class);

        List<EvidenceItem> ok = new ArrayList<>(Fixtures.request().evidence());
        ok.add(Fixtures.ev("E-DEP-0", EvidenceType.DEPLOYMENT, "2026-03-12T08:00:00Z"));
        assertThatCode(() -> v.validate(Fixtures.with(ok))).doesNotThrowAnyException();

        List<EvidenceItem> late = new ArrayList<>(Fixtures.request().evidence());
        late.add(Fixtures.ev("E-DEP-9", EvidenceType.DEPLOYMENT, "2026-03-12T12:00:00Z"));
        assertThatThrownBy(() -> v.validate(Fixtures.with(late))).isInstanceOf(InvalidRequestException.class);
    }
}
