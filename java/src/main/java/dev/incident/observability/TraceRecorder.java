package dev.incident.observability;

import dev.incident.observability.InvestigationTrace.Outcome;
import dev.incident.observability.InvestigationTrace.StepTrace;
import dev.incident.validation.InvestigationException;
import dev.incident.workflow.ModelResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Records one investigation: a correlation id (also placed in the logging MDC so every log line carries it)
 * and one StepTrace per workflow step. Logs step name, duration, outcome and token counts only - never evidence
 * content or credentials.
 */
public class TraceRecorder {
    public static final String MDC_KEY = "investigationId";
    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final String investigationId = UUID.randomUUID().toString();
    private final List<StepTrace> steps = Collections.synchronizedList(new ArrayList<>());

    public String investigationId() { return investigationId; }

    public InvestigationTrace snapshot() {
        synchronized (steps) {
            return new InvestigationTrace(investigationId, List.copyOf(steps));
        }
    }

    /** Runs a model step, recording timing and outcome. Safe to call from worker threads. */
    public <T> T modelStep(String name, Supplier<ModelResult<T>> body) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, investigationId);
        long start = System.nanoTime();
        try {
            ModelResult<T> result = body.get();
            record(name, start, Outcome.OK, null, result.promptTokens(), result.completionTokens());
            return result.value();
        } catch (RuntimeException e) {
            record(name, start, Outcome.FAILED, safeError(e), null, null);
            throw e;
        } finally {
            restore(previous);
        }
    }

    /** Our own validation messages are safe to log; provider exception text may echo request data or secrets. */
    private static String safeError(RuntimeException e) {
        return e instanceof InvestigationException ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Runs a deterministic (non-model) step. */
    public void localStep(String name, Runnable body) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, investigationId);
        long start = System.nanoTime();
        try {
            body.run();
            record(name, start, Outcome.OK, null, null, null);
        } catch (RuntimeException e) {
            record(name, start, Outcome.FAILED, e.getMessage(), null, null);
            throw e;
        } finally {
            restore(previous);
        }
    }

    private void record(String name, long startNanos, Outcome outcome, String error, Integer pt, Integer ct) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        steps.add(new StepTrace(name, ms, outcome, error, pt, ct));
        if (outcome == Outcome.OK) {
            log.info("step={} outcome=OK durationMs={} promptTokens={} completionTokens={}", name, ms, pt, ct);
        } else {
            log.warn("step={} outcome=FAILED durationMs={} error={}", name, ms, error);
        }
    }

    private static void restore(String previous) {
        if (previous == null) MDC.remove(MDC_KEY); else MDC.put(MDC_KEY, previous);
    }
}
