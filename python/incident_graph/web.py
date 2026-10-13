"""HTTP API + UI for the LangGraph implementation. Same endpoints and error format as the Java service:

    POST /api/incidents/investigate   GET /api/scenarios   GET /api/info   GET /  (shared UI from ../ui)

Requires the optional web dependencies:  pip install -e '.[web]'
"""
from __future__ import annotations

import uuid
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from .errors import AssessmentValidationError, InvalidRequestError, InvestigationError, ModelFailureError
from .graph import investigate
from .llm import StructuredLLM
from .models import InvestigationRequest
from .scoring import load_scenarios

REPO_ROOT = Path(__file__).resolve().parents[2]
LABEL = "Python \u00b7 LangGraph \u00b7 Amazon Bedrock"
# Read-only API with no secrets; allow the UI served from the other backend on localhost to call this one.
LOCAL_ORIGINS = r"http://(localhost|127\.0\.0\.1)(:\d+)?"


def _problem(status: int, title: str, investigation_id: str | None, violations: list[str]) -> JSONResponse:
    return JSONResponse(
        {"type": "about:blank", "title": title, "status": status, "detail": title,
         "investigationId": investigation_id, "violations": violations},
        status_code=status, media_type="application/problem+json")


def create_app(llm: StructuredLLM, scenarios_dir: Path | None = None, ui_dir: Path | None = None) -> FastAPI:
    scenarios_dir = scenarios_dir or REPO_ROOT / "scenarios"
    ui_dir = ui_dir or REPO_ROOT / "ui"
    app = FastAPI(title="incident-graph", docs_url=None, redoc_url=None)
    app.add_middleware(CORSMiddleware, allow_origin_regex=LOCAL_ORIGINS, allow_methods=["GET", "POST"],
                       allow_headers=["content-type"], expose_headers=["X-Investigation-Id"])

    @app.exception_handler(RequestValidationError)
    async def _bad_request(_: Request, exc: RequestValidationError):
        v = [f"{'.'.join(str(p) for p in e['loc'][1:])} {e['msg']}" for e in exc.errors()]
        return _problem(400, "Invalid investigation request", None, v)

    @app.exception_handler(InvestigationError)
    async def _investigation_error(_: Request, exc: InvestigationError):
        iid = getattr(exc, "investigation_id", None)
        if isinstance(exc, InvalidRequestError):
            return _problem(400, str(exc), iid, exc.violations)
        if isinstance(exc, AssessmentValidationError):
            return _problem(422, str(exc), iid, exc.violations)
        if isinstance(exc, ModelFailureError):  # provider text is never exposed
            return _problem(502, str(exc), iid, [])
        return _problem(500, "Investigation failed", iid, [])

    @app.get("/api/info")
    def info():
        return {"backend": LABEL}

    @app.get("/api/scenarios")
    def scenarios():
        out = []
        for s in load_scenarios(scenarios_dir):
            out.append({
                "name": s.name,
                "request": s.request.model_dump(mode="json", by_alias=True),
                "expected": {"rootCause": s.expected.expected_root_cause.value, "notes": s.expected.notes},
            })
        return out

    @app.post("/api/incidents/investigate")
    def post_investigate(request: InvestigationRequest):
        # Sync endpoint: FastAPI runs it in a worker thread, so the event loop is not blocked by the model calls.
        investigation_id = str(uuid.uuid4())
        try:
            result = investigate(request, llm)
        except InvestigationError as e:
            e.investigation_id = investigation_id  # type: ignore[attr-defined]
            raise
        body = {
            "assessment": result.assessment.model_dump(mode="json", by_alias=True),
            "trace": {"investigationId": investigation_id,
                      "steps": [s.model_dump(mode="json", by_alias=True) for s in result.steps]},
        }
        return JSONResponse(body, headers={"X-Investigation-Id": investigation_id})

    if ui_dir.is_dir():  # mounted last so it never shadows /api routes
        app.mount("/", StaticFiles(directory=ui_dir, html=True), name="ui")
    return app
