class InvestigationError(Exception):
    """Base class; `violations` holds our own (safe to expose) messages."""

    def __init__(self, message: str, violations: list[str] | None = None):
        super().__init__(message)
        self.violations = violations or []


class InvalidRequestError(InvestigationError):
    """HTTP 400 equivalent."""


class AssessmentValidationError(InvestigationError):
    """HTTP 422 equivalent: model output broke an application invariant."""


class ModelFailureError(InvestigationError):
    """HTTP 502 equivalent: provider/model error. The provider message is deliberately not kept."""
