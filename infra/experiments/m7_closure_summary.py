"""Audit #157 source identity and aggregate already recalculated evidence; no provider calls.

Run the Java raw/review/routing recalculators first. This script does not replace
their oracle checks or the human review of complete generated answers.
All input and output artifacts stay under the ignored root build directory.
"""

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKLOADS = ("customer", "management", "ops")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(directory, filename):
    return json.loads((directory / filename).read_text(encoding="utf-8"))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def evidence_directory(name, prefix):
    require(name.startswith(prefix) and Path(name).name == name and ".." not in name,
            "Expected a named evidence directory under root build")
    return ROOT / "build" / name


def current_sources(manifest, comparison=False):
    sources = manifest["sourceDigests"]
    expected = ({"GeminiAdapter", "ProviderBudget", "ProviderProtocol", "ModelRouter", "ModelComparisonRunner",
                 "ComparisonReview", "GradleConfiguration"} if comparison else {
        "ai/flow/RepresentativeContext", "ai/flow/RepresentativeFlows", "ai/flow/RepresentativeRouterProfile",
        "ai/runtime/AgentRuntime", "ai/runtime/RuntimeContract", "ai/router/GeminiAdapter", "ai/router/ModelRouter",
        "ai/router/ProviderProtocol", "ai/router/ProviderBudget", "ai/router/RepresentativeFlowEvidence",
        "ai/router/ComparisonReview", "ai/router/ModelComparisonRunner", "mcp/RepresentativeFlowIntegrationTests"})
    require(set(sources) == expected, "Missing or unexpected execution source identity")
    for name, expected in sources.items():
        if comparison:
            path = "backend/build.gradle" if name == "GradleConfiguration" else (
                "backend/src/" + ("test" if name in ("ModelComparisonRunner", "ComparisonReview") else "main")
                + "/java/com/slotq/ai/router/" + name + ".java")
        else:
            kind = "test" if name in ("ai/router/RepresentativeFlowEvidence", "ai/router/ComparisonReview",
                                      "ai/router/ModelComparisonRunner", "mcp/RepresentativeFlowIntegrationTests") else "main"
            path = "backend/src/" + kind + "/java/com/slotq/" + name + ".java"
        require(digest(ROOT / path) == expected, "Current source differs from actual execution: " + name)
    return len(sources)


def audit(comparison, flows):
    manifest = read(comparison, "manifest.json")
    comparison_sources = current_sources(manifest, comparison=True)
    recalculation = read(comparison, "recalculation.json")
    require(recalculation["actualSourceDigests"] == manifest["sourceDigests"], "Comparison source provenance mismatch")
    require(recalculation["contentReviewSha256"] == digest(comparison / "content-review.json"), "Comparison review changed")
    for name, expected in recalculation["calculationSourceDigests"].items():
        require(expected == manifest["sourceDigests"][name], "Calculator changed after actual comparison")
    summary = read(comparison, "summary.json")
    require(len(summary) == 6 and all(s["complete"] and s["contentReviewStatus"] == "reviewed" for s in summary),
            "Incomplete or unreviewed two-model comparison")
    routing = read(comparison, "routing.json")
    require({r["snapshot"]["workload"] for r in routing} == set(WORKLOADS), "Missing workload routing")
    selected = {}
    eligible = {}
    for decision in routing:
        workload = decision["snapshot"]["workload"]
        require(decision["selected"] is not None, "Fresh routing has no eligible candidate: " + workload)
        selected[workload] = decision["selected"]
        eligible[workload] = sorted(decision["scores"])
    flow_summary = []
    for flow in WORKLOADS:
        directory = flows / flow
        flow_manifest = read(directory, "manifest.json")
        require(flow_manifest["sourceRevision"] == manifest["sourceRevision"], "Flow/comparison source revisions differ")
        require(flow_manifest["specification"]["model"] in eligible[flow], "Adopted flow model is no longer eligible: " + flow)
        source_count = current_sources(flow_manifest)
        calculation = read(directory, "recalculation.json")
        require(calculation["reviewSha256"] == digest(directory / "content-review.json"), "Flow review changed")
        require(calculation["attemptsSha256"] == digest(directory / "attempts.jsonl"), "Flow raw changed")
        require(calculation["calculatorSha256"] == flow_manifest["sourceDigests"]["ai/router/RepresentativeFlowEvidence"],
                "Flow calculator changed after execution")
        entries = read(directory, "summary.json")
        require(len(entries) == 1 and entries[0]["workload"] == flow and entries[0]["complete"], "Flow not complete: " + flow)
        flow_summary.append(entries[0] | {"currentSourceDigestsVerified": source_count})
    return {"sourceRevision": manifest["sourceRevision"], "comparison": summary,
            "comparisonCurrentSourceDigestsVerified": comparison_sources,
            "freshSelectedModels": selected, "freshEligibleModels": eligible, "flows": flow_summary,
            "fallback": "unsupported; terminal policy; no actual fallback claimed",
            "scope": "bounded synthetic comparison and demo flows; no production quality/SLA/release qualification"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--comparison", required=True)
    parser.add_argument("--flows", required=True)
    args = parser.parse_args()
    report = audit(evidence_directory(args.comparison, "model-router-"),
                   evidence_directory(args.flows, "representative-flow-"))
    output = ROOT / "build/issue-157/closure-summary.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Verified current execution sources, six model/workload summaries and three complete flows: " + str(output))


if __name__ == "__main__":
    main()
