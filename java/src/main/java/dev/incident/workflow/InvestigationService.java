package dev.incident.workflow;

import dev.incident.domain.AnalystReport;
import dev.incident.domain.IncidentAssessment;
import dev.incident.domain.InvestigationRequest;
import dev.incident.domain.InvestigationResponse;
import dev.incident.domain.TriageResult;
import dev.incident.observability.TraceRecorder;
import dev.incident.validation.AssessmentValidator;
import dev.incident.validation.InvestigationException;
import dev.incident.validation.ModelFailureException;
import dev.incident.validation.RequestValidator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Bounded, application-orchestrated workflow:
 * triage -> (runtime analyst || change analyst) -> verifier -> deterministic validation.
 * Agents never converse and have no tools; the application owns control flow.
 */
@Service
public class InvestigationService {

    private final ModelClient model;
    private final EvidenceRouter router;
    private final RequestValidator requestValidator;
    private final AssessmentValidator assessmentValidator;
    private final ExecutorService analystPool = Executors.newVirtualThreadPerTaskExecutor();

    public InvestigationService(ModelClient model, EvidenceRouter router,
                                RequestValidator requestValidator, AssessmentValidator assessmentValidator) {
        this.model = model;
        this.router = router;
        this.requestValidator = requestValidator;
        this.assessmentValidator = assessmentValidator;
    }

    public InvestigationResponse investigate(InvestigationRequest request) {
        TraceRecorder trace = new TraceRecorder();
        try {
            return run(request, trace);
        } catch (InvestigationException e) {
            e.investigationId(trace.investigationId());
            throw e;
        }
    }

    private InvestigationResponse run(InvestigationRequest request, TraceRecorder trace) {
        trace.localStep("validate-request", () -> requestValidator.validate(request));
        String window = request.window().start() + " to " + request.window().end();
        String id = request.incidentId();

        TriageResult triage = guarded("triage", trace, () -> model.call("triage", Prompts.TRIAGE,
                Prompts.triageUser(id, request.title(), window, router.forTriage(request)), TriageResult.class));

        // The two analysts are independent, so run them concurrently; join before the verifier.
        CompletableFuture<AnalystReport> runtimeF = CompletableFuture.supplyAsync(() ->
                analyst("runtime-analyst", Prompts.RUNTIME_ANALYST, router.forRuntimeAnalyst(request), request, triage, window, trace),
                analystPool);
        CompletableFuture<AnalystReport> changeF = CompletableFuture.supplyAsync(() ->
                analyst("change-analyst", Prompts.CHANGE_ANALYST, router.forChangeAnalyst(request), request, triage, window, trace),
                analystPool);
        AnalystReport runtime = join(runtimeF);
        AnalystReport change = join(changeF);

        IncidentAssessment assessment = guarded("verifier", trace, () -> model.call("verifier", Prompts.VERIFIER,
                Prompts.verifierUser(id, window, triage, runtime, change, request.evidence()), IncidentAssessment.class));

        trace.localStep("validate", () -> assessmentValidator.validate(assessment, request));
        return new InvestigationResponse(assessment, trace.snapshot());
    }

    private AnalystReport analyst(String step, String system, java.util.List<dev.incident.domain.EvidenceItem> items,
                                  InvestigationRequest request, TriageResult triage, String window, TraceRecorder trace) {
        AnalystReport report = guarded(step, trace, () -> model.call(step, system,
                Prompts.analystUser(request.incidentId(), window, triage, items), AnalystReport.class));
        assessmentValidator.validateAnalyst(step, report, request);
        return report;
    }

    /** Runs a model call as a traced step, translating provider errors into ModelFailureException. */
    private <T> T guarded(String step, TraceRecorder trace, Supplier<ModelResult<T>> call) {
        try {
            return trace.modelStep(step, call);
        } catch (InvestigationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ModelFailureException(step, e);
        }
    }

    private static <T> T join(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }
}
