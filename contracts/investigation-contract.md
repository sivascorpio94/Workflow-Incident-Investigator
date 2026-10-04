# Investigation contract (shared by the Java and Python implementations)

`POST /api/incidents/investigate` takes an **InvestigationRequest** and returns an **InvestigationResponse**.
The expected root cause lives only in `scenarios/*/incident.yaml` and is **never** part of a request.

## Request

```json
{
  "incidentId": "WF-2026-014",
  "title": "string",
  "window": { "start": "ISO-8601 instant", "end": "ISO-8601 instant" },
  "evidence": [
    { "id": "E-ALERT-1", "type": "ALERT|METRIC|LOG|DEPLOYMENT|RUNBOOK",
      "source": "string", "timestamp": "ISO-8601 instant", "content": "string" }
  ]
}
```

Request rules (HTTP 400 on violation): non-blank ids/title/content; evidence ids unique; `window.start < window.end`;
at least one `ALERT`; ALERT/METRIC/LOG timestamps inside the window; DEPLOYMENT may precede the window but not follow it;
RUNBOOK timestamps are unconstrained.

## Response

```json
{
  "assessment": {
    "incidentId": "string",
    "summary": "string",
    "severity": "LOW|MEDIUM|HIGH|CRITICAL",
    "findings":   [ { "claim": "string", "kind": "FACT|HYPOTHESIS", "evidenceIds": ["E-..."] } ],
    "hypotheses": [ { "category": "LOAD_SURGE|EXECUTOR_CONFIG_REGRESSION|DATABASE_SATURATION|RETRY_STORM|OTHER|UNDETERMINED",
                      "description": "string", "confidence": 0.0,
                      "supportingEvidenceIds": [], "contradictingEvidenceIds": [] } ],
    "unknowns": ["string"],
    "recommendations": [ { "action": "string", "rationale": "string", "readOnly": true } ],
    "confidence": 0.0,
    "remediationExecuted": false
  },
  "trace": { "investigationId": "uuid",
             "steps": [ { "name": "triage|runtime-analyst|change-analyst|verifier|validate",
                          "durationMs": 0, "outcome": "OK|FAILED", "error": null,
                          "promptTokens": null, "completionTokens": null } ] }
}
```

Hypotheses are ordered best-supported first; the first one is the diagnosis.

Application invariants (HTTP 422 if the model output breaks them):
every cited evidence id exists in the request; every `FACT` cites at least one id; `confidence` values are in [0,1];
`remediationExecuted` is `false`; every recommendation is `readOnly: true`; no text claims remediation was carried out.

## Errors

RFC 7807 `application/problem+json` with `investigationId` and `violations` properties.
400 invalid request - 422 model output failed validation - 502 model/provider error.
