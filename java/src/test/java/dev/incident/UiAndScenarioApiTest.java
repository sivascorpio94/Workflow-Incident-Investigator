package dev.incident;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

/** The demo UI is served from the shared ../ui folder; scenario endpoints back its dropdown. No model involved. */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "incident.model.provider=stub"})
@AutoConfigureMockMvc
class UiAndScenarioApiTest {
    @Autowired MockMvc mvc;

    /** These tests never call a model; the workflow just needs some ModelClient bean to exist. */
    @TestConfiguration
    static class NoModel {
        @Bean dev.incident.workflow.ModelClient modelClient() {
            return new dev.incident.workflow.ModelClient() {
                @Override public <T> dev.incident.workflow.ModelResult<T> call(String step, String system, String user, Class<T> type) {
                    throw new UnsupportedOperationException("no model in this test");
                }
            };
        }
    }

    @Test void servesTheSharedUiAtRoot() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Incident Investigator")));
    }

    @Test void listsScenariosWithRequestAndExpectedAnswer() throws Exception {
        mvc.perform(get("/api/scenarios")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[?(@.name=='camunda-job-backlog')].expected.rootCause").value("LOAD_SURGE"))
                .andExpect(jsonPath("$[?(@.name=='camunda-job-backlog')].request.incidentId").value("WF-2026-014"));
    }

    @Test void reportsBackendLabel() throws Exception {
        mvc.perform(get("/api/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.backend").value(org.hamcrest.Matchers.containsString("Spring AI")));
    }

    @Test void corsAllowsLocalhostOriginsOnly() throws Exception {
        mvc.perform(options("/api/incidents/investigate").header("Origin", "http://localhost:8000")
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8000"));
        mvc.perform(options("/api/incidents/investigate").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isForbidden());
    }
}
