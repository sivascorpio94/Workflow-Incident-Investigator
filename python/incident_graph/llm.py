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


def _isolate_from_aws_profiles_when_using_api_key() -> None:
    """With a Bedrock API key (AWS_BEARER_TOKEN_BEDROCK) no AWS profile is needed, but botocore still reads
    ~/.aws/config while building the client. A stale or unsupported profile (e.g. an expired `aws login` session,
    which needs botocore[crt]) then fails client creation before the key is ever used. Point botocore at empty config
    files for this process so only the key is used. Affects this process only; real files are untouched."""
    if os.environ.get("AWS_BEARER_TOKEN_BEDROCK"):
        os.environ["AWS_CONFIG_FILE"] = os.devnull
        os.environ["AWS_SHARED_CREDENTIALS_FILE"] = os.devnull
        for var in ("AWS_PROFILE", "AWS_DEFAULT_PROFILE"):
            os.environ.pop(var, None)


class BedrockLLM:
    """Bedrock Converse chat model returning typed output. No tools are bound to the model."""

    def __init__(self, model_id: str | None = None, region: str | None = None):
        from langchain_aws import ChatBedrockConverse  # imported lazily so tests need no AWS libs configured

        _isolate_from_aws_profiles_when_using_api_key()

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
