"""Offline checks that the Bedrock wrapper authenticates with the API key and ignores broken AWS profiles."""
import os

import pytest

from incident_graph.llm import BedrockLLM
from incident_graph.models import TriageResult


class _Captured(Exception):
    pass


def test_api_key_is_used_and_broken_profile_is_ignored(tmp_path, monkeypatch):
    # A profile like the one `aws login` writes; it must not be consulted when an API key is set.
    cfg = tmp_path / "config"
    cfg.write_text("[default]\nlogin_session = arn:aws:sts::123456789012:assumed-role/X/Y\nregion = us-east-2\n")
    monkeypatch.setenv("AWS_CONFIG_FILE", str(cfg))
    monkeypatch.setenv("AWS_PROFILE", "default")
    monkeypatch.setenv("AWS_BEARER_TOKEN_BEDROCK", "ABSK-test-token")
    monkeypatch.setenv("AWS_REGION", "us-east-2")
    for k in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN"):
        monkeypatch.delenv(k, raising=False)

    llm = BedrockLLM("us.anthropic.claude-haiku-4-5-20251001-v1:0")
    assert os.environ["AWS_CONFIG_FILE"] == os.devnull and "AWS_PROFILE" not in os.environ

    seen = {}

    def grab(request, **kwargs):
        auth = request.headers.get("Authorization")
        seen["auth"] = auth.decode() if isinstance(auth, bytes) else auth
        seen["url"] = request.url
        raise _Captured()  # stop before any network call

    llm._chat.client.meta.events.register("before-send.bedrock-runtime.Converse", grab)
    with pytest.raises(Exception):
        llm("triage", "sys", "user", TriageResult)
    assert seen["auth"] == "Bearer ABSK-test-token"
    assert "bedrock-runtime.us-east-2.amazonaws.com" in seen["url"]
