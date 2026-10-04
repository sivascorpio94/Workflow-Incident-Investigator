package dev.incident.api;

import dev.incident.domain.InvestigationRequest;
import dev.incident.domain.InvestigationResponse;
import dev.incident.workflow.InvestigationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
public class InvestigationController {
    private final InvestigationService service;

    public InvestigationController(InvestigationService service) {
        this.service = service;
    }

    @PostMapping("/investigate")
    public ResponseEntity<InvestigationResponse> investigate(@Valid @RequestBody InvestigationRequest request) {
        InvestigationResponse response = service.investigate(request);
        return ResponseEntity.ok()
                .header("X-Investigation-Id", response.trace().investigationId())
                .body(response);
    }
}
