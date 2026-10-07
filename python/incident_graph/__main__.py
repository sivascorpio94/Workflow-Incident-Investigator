"""CLI:  python -m incident_graph investigate ../scenarios/<name>/sample-request.json
        python -m incident_graph eval [--langsmith]
Tracing: set LANGSMITH_TRACING=true, LANGSMITH_API_KEY, LANGSMITH_PROJECT (keys stay in the environment)."""
from __future__ import annotations

import argparse
import sys

from .errors import InvestigationError
from .graph import investigate
from .llm import BedrockLLM
from .models import InvestigationRequest
from .scoring import load_scenarios, score


def _load_dotenv() -> None:
    """Load ../.env or ./.env into the environment if python-dotenv is installed. Existing variables win."""
    try:
        from dotenv import load_dotenv
    except ImportError:
        return
    for path in (".env", "../.env"):
        load_dotenv(path, override=False)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="incident_graph")
    sub = p.add_subparsers(dest="cmd", required=True)
    inv = sub.add_parser("investigate")
    inv.add_argument("request")
    ev = sub.add_parser("eval")
    ev.add_argument("--dir", default="../scenarios")
    ev.add_argument("--langsmith", action="store_true", help="upload dataset and run as a LangSmith experiment")
    args = p.parse_args(argv)
    _load_dotenv()
    llm = BedrockLLM()

    if args.cmd == "investigate":
        req = InvestigationRequest.model_validate_json(open(args.request).read())
        try:
            print(investigate(req, llm).model_dump_json(by_alias=True, indent=2))
        except InvestigationError as e:
            print(f"{type(e).__name__}: {e}\n  " + "\n  ".join(e.violations), file=sys.stderr)
            return 1
        return 0

    if args.langsmith:
        from .langsmith_eval import run_eval
        run_eval(llm, args.dir)
        return 0
    total = 0.0
    scenarios = load_scenarios(args.dir)
    for s in scenarios:
        try:
            a = investigate(s.request, llm).assessment
        except InvestigationError as e:
            a = None
            print(f"{s.name}: FAILED {type(e).__name__}")
        sc = score(a, s.request, s.expected)
        total += sc["overall"]
        got = a.hypotheses[0].category.value if a and a.hypotheses else "-"
        print(f"{s.name:30s} expected={s.expected.expected_root_cause.value:28s} got={got:28s} overall={sc['overall']:.2f}")
    print(f"MEAN overall = {total / max(1, len(scenarios)):.2f} over {len(scenarios)} scenarios")
    return 0


if __name__ == "__main__":
    sys.exit(main())
