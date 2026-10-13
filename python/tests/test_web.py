"""API tests with a fake LLM through FastAPI's TestClient: no Bedrock, no network."""
import json

import pytest
from fastapi.testclient import TestClient

from incident_graph.web import create_app
from tests.test_graph import FakeLLM, SAMPLE, good_assessment
from incident_graph.models import ClaimKind, Finding


def client(llm=None):
    return TestClient(create_app(llm or FakeLLM()), raise_server_exceptions=False)


def sample():
    return json.loads(SAMPLE.read_text())


def test_info_and_scenarios_and_ui():
    c = client()
    assert "Python" in c.get("/api/info").json()["backend"]
    s = c.get("/api/scenarios").json()
    assert len(s) == 6
    first = next(x for x in s if x["name"] == "camunda-job-backlog")
    assert first["expected"]["rootCause"] == "LOAD_SURGE" and first["request"]["incidentId"] == "WF-2026-014"
    page = c.get("/")
    assert page.status_code == 200 and "text/html" in page.headers["content-type"]


def test_investigate_matches_contract():
    r = client().post("/api/incidents/investigate", json=sample())
    assert r.status_code == 200
    body = r.json()
    assert r.headers["X-Investigation-Id"] == body["trace"]["investigationId"]
    assert body["assessment"]["remediationExecuted"] is False
    assert body["assessment"]["hypotheses"][0]["category"] == "LOAD_SURGE"
    assert [s["name"] for s in body["trace"]["steps"]][0] == "validate-request"
    assert len(body["trace"]["steps"]) == 6


def test_errors_use_problem_json_with_expected_statuses():
    c = client()
    dup = sample()
    dup["evidence"][2]["id"] = dup["evidence"][1]["id"]
    r = c.post("/api/incidents/investigate", json=dup)
    assert r.status_code == 400 and r.headers["content-type"].startswith("application/problem+json")
    assert any("duplicate" in v for v in r.json()["violations"])

    assert c.post("/api/incidents/investigate", json={"incidentId": "", "evidence": []}).status_code == 400

    bad = good_assessment(findings=[Finding(claim="x", kind=ClaimKind.FACT, evidence_ids=["E-MADE-UP"])])
    r = client(FakeLLM(verifier=bad)).post("/api/incidents/investigate", json=sample())
    assert r.status_code == 422 and any("E-MADE-UP" in v for v in r.json()["violations"])
    assert r.json()["investigationId"]

    r = client(FakeLLM(fail_step="verifier")).post("/api/incidents/investigate", json=sample())
    assert r.status_code == 502 and "secret-token-123" not in r.text


def test_cors_allows_localhost_origin_only():
    c = client()
    ok = c.options("/api/incidents/investigate", headers={
        "Origin": "http://localhost:8080", "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type"})
    assert ok.headers.get("access-control-allow-origin") == "http://localhost:8080"
    bad = c.options("/api/incidents/investigate", headers={
        "Origin": "https://evil.example", "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type"})
    assert "access-control-allow-origin" not in bad.headers
