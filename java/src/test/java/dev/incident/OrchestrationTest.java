package dev.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.incident.domain.*;
import dev.incident.workflow.ModelClient;
import dev.incident.workflow.ModelResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Full orchestration through the real REST layer with a stubbed model: no Bedrock credentials needed. */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "incident.model.provider=stub"})
@AutoConfigureMockMvc
class OrchestrationTest {

    /** Scriptable stub. Analyst steps rendezvous on a latch: they only both pass if they run concurrently. */
    static class StubModel implements ModelClient {
        final ConcurrentHashMap<String, String> prompts = new ConcurrentHashMap<>();
        final CountDownLatch analystsRunning = new CountDownLatch(2);
        volatile boolean analystsRanInParallel = false;
        volatile Supplier<IncidentAssessment> verifier = Fixtures::assessment;
        volatile String failStep = null;

        @Override
        @SuppressWarnings("unchecked")
        public <T> ModelResult<T> call(String step, String system, String user, Class<T> type) {
            prompts.put(step, user);
            if (step.equals(failStep)) throw new IllegalStateException("simulated provider outage secret-token-123");
            Object out = switch (step) {
                case "triage" -> new TriageResult("Backlog alert", "wf-engine", List.of("backlog"), List.of("E-ALERT-1"));
                case "runtime-analyst", "change-analyst" -> {
                    analystsRunning.countDown();
                    try {
                        analystsRanInParallel = analystsRunning.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    yield new AnalystReport(List.of("E-ALERT-1"), List.of(), List.of("q"));
                }
                case "verifier" -> verifier.get();
                default -> throw new IllegalArgumentException(step);
            };
            return new ModelResult<>((T) out, 100, 50);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean StubModel stubModel() { return new StubModel(); }
    }

    @Autowired MockMvc mvc;
    @Autowired StubModel stub;

    @BeforeEach void reset() {
        stub.verifier = Fixtures::assessment;
        stub.failStep = null;
    }

    private static String body() throws Exception {
        return Files.readString(Path.of("../scenarios/camunda-job-backlog/sample-request.json"));
    }

    private String send(String json) throws Exception {
        return mvc.perform(post("/api/incidents/investigate").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getContentAsString();
    }

    /** Verifier stub returns citations valid for the sample scenario. */
    private void useSampleCitations() {
        stub.verifier = () -> Fixtures.with(b -> b.findings(List.of(new Finding("Starts surged", ClaimKind.FACT, List.of("E-MET-1"))))
                .hypotheses(List.of(new Hypothesis(CauseCategory.LOAD_SURGE, "d", 0.7, List.of("E-MET-1", "E-LOG-1"), List.of("E-DEP-1")))));
    }

    @Test void happyPathReturnsTypedAssessmentWithTrace() throws Exception {
        useSampleCitations();
        mvc.perform(post("/api/incidents/investigate").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Investigation-Id"))
                .andExpect(jsonPath("$.assessment.remediationExecuted").value(false))
                .andExpect(jsonPath("$.assessment.hypotheses[0].category").value("LOAD_SURGE"))
                .andExpect(jsonPath("$.trace.steps.length()").value(6))
                .andExpect(jsonPath("$.trace.steps[?(@.name=='verifier')].promptTokens").value(100));
        assertThat(stub.analystsRanInParallel).as("analysts overlapped in time").isTrue();
    }

    @Test void analystsReceiveOnlyTheirEvidenceTypesAndVerifierSeesEverything() throws Exception {
        useSampleCitations();
        send(body());
        assertThat(stub.prompts.get("runtime-analyst")).contains("[E-MET-1]").contains("[E-LOG-1]").doesNotContain("[E-DEP-1]");
        assertThat(stub.prompts.get("change-analyst")).contains("[E-DEP-1]").contains("[E-RB-1]").doesNotContain("[E-MET-1]");
        assertThat(stub.prompts.get("verifier")).contains("[E-MET-1]").contains("[E-DEP-1]").contains("Runtime analyst report");
    }

    @Test void expectedCauseIsNeverSentToTheModel() throws Exception {
        useSampleCitations();
        send(body());
        assertThat(String.join(" ", stub.prompts.values())).doesNotContain("expectedRootCause").doesNotContain("mustNotConclude");
    }

    @Test void unknownEvidenceIdFromModelIsRejectedWith422() throws Exception {
        stub.verifier = () -> Fixtures.with(b -> b.findings(List.of(new Finding("x", ClaimKind.FACT, List.of("E-MADE-UP")))));
        mvc.perform(post("/api/incidents/investigate").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.containsString("E-MADE-UP")))
                .andExpect(jsonPath("$.investigationId").isNotEmpty());
    }

    @Test void remediationClaimIsRejectedWith422() throws Exception {
        useSampleCitations();
        var ok = stub.verifier;
        stub.verifier = () -> {
            var a = ok.get();
            return new IncidentAssessment(a.incidentId(), a.summary(), a.severity(), a.findings(), a.hypotheses(),
                    a.unknowns(), a.recommendations(), a.confidence(), true);
        };
        mvc.perform(post("/api/incidents/investigate").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test void providerFailureIs502AndDoesNotLeakProviderMessage() throws Exception {
        stub.failStep = "verifier";
        String resp = mvc.perform(post("/api/incidents/investigate").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.investigationId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(resp).doesNotContain("secret-token-123");
    }

    @Test void invalidRequestsFailClearlyWith400() throws Exception {
        String dup = body().replace("\"E-MET-2\"", "\"E-MET-1\"");
        mvc.perform(post("/api/incidents/investigate").contentType(MediaType.APPLICATION_JSON).content(dup))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.containsString("duplicate")));

        mvc.perform(post("/api/incidents/investigate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentId\":\"\",\"evidence\":[]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/incidents/investigate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }
}
