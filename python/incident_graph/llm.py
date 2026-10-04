"""The only seam to a model. Tests pass a fake; production uses Bedrock via langchain-aws."""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Protocol, TypeVar

from pydantic import BaseModel

T = TypeVar("T", bound=BaseModel)


@dataclass
class LLMResult:
    value: BaseModel
    prompt_tokens: int | None = None
    completion_tokens: int | None = None


class StructuredLLM(Protocol):
    def __call__(self, step: str, system: str, user: str, schema: type[T]) -> LLMResult: ...


class BedrockLLM:
    """Bedrock Converse chat model returning typed output. No tools are bound to the model."""

    def __init__(self, model_id: str | None = None, region: str | None = None):
        from langchain_aws import ChatBedrockConverse  # imported lazily so tests need no AWS libs configured

        model_id = model_id or os.environ.get("BEDROCK_MODEL_ID")
        if not model_id:
            raise RuntimeError("Set BEDROCK_MODEL_ID to a chat model enabled for your account and region")
        self._chat = ChatBedrockConverse(
            model=model_id, region_name=region or os.environ.get("AWS_REGION", "us-east-2"),
            temperature=0.1, max_tokens=4000,
        )

    def __call__(self, step: str, system: str, user: str, schema: type[T]) -> LLMResult:
        runnable = self._chat.with_structured_output(schema, include_raw=True)
        out = runnable.invoke([("system", system), ("user", user)], config={"run_name": step})
        if out.get("parsing_error") or out.get("parsed") is None:
            raise ValueError(f"unparseable model output in step {step}")
        usage = getattr(out.get("raw"), "usage_metadata", None) or {}
        return LLMResult(out["parsed"], usage.get("input_tokens"), usage.get("output_tokens"))
