"""Upload scenarios as a LangSmith dataset and score the graph with the shared rubric.

Requires LANGSMITH_API_KEY (and AWS/Bedrock access). Inputs are the request ONLY; the expected answer is stored
as the dataset's reference output and is never passed to the graph.
"""
from __future__ import annotations

from langsmith import Client, evaluate

from .errors import InvestigationError
from .graph import investigate
from .models import InvestigationRequest
from .scoring import DIMENSIONS, Expected, load_scenarios, score

DATASET = "incident-investigator-scenarios"


def ensure_dataset(client: Client, scenarios_dir: str):
    if client.has_dataset(dataset_name=DATASET):
        return client.read_dataset(dataset_name=DATASET)
    ds = client.create_dataset(DATASET, description="Fictional workflow-engine backlog incidents")
    for s in load_scenarios(scenarios_dir):
        client.create_example(
            inputs={"request": s.request.model_dump(mode="json", by_alias=True)},
            outputs={"expected": s.expected.model_dump(mode="json")},
            metadata={"scenario": s.name}, dataset_id=ds.id)
    return ds


def run_eval(llm, scenarios_dir: str = "../scenarios", experiment_prefix: str = "graph"):
    client = Client()
    ensure_dataset(client, scenarios_dir)

    def target(inputs: dict) -> dict:
        req = InvestigationRequest.model_validate(inputs["request"])
        try:
            resp = investigate(req, llm)
            return {"assessment": resp.assessment.model_dump(mode="json", by_alias=True), "error": None}
        except InvestigationError as e:
            return {"assessment": None, "error": f"{type(e).__name__}: {e}"}

    def evaluator_for(dim: str):
        def ev(inputs: dict, outputs: dict, reference_outputs: dict):
            from .models import IncidentAssessment
            req = InvestigationRequest.model_validate(inputs["request"])
            a = IncidentAssessment.model_validate(outputs["assessment"]) if outputs.get("assessment") else None
            return {"key": dim, "score": score(a, req, Expected.model_validate(reference_outputs["expected"]))[dim]}
        ev.__name__ = dim
        return ev

    return evaluate(target, data=DATASET, evaluators=[evaluator_for(d) for d in DIMENSIONS + ["overall"]],
                    experiment_prefix=experiment_prefix, max_concurrency=2)
