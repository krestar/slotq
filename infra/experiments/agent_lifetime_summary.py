"""Recalculate #156 latency from fixed historical cohorts; never contact a provider.

Run from the repository root. Raw inputs and the generated report remain gitignored.
Each revision/context is reported separately, including failed and partial cohorts.
"""

import argparse
from collections import Counter, defaultdict
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path


COHORTS = (
    "model-router-comparison",
    "model-router-corrective-v3",
    "representative-flow-v1",
    "representative-flow-v2",
    "representative-flow-v2-complete",
    "representative-flow-v2-operators-first",
    "representative-flow-v3",
    "representative-flow-v3-complete",
    "representative-flow-final",
    "representative-flow-final-complete",
    "representative-flow-recheck-20261014",
    "representative-flow-required-20261014",
    "representative-flow-final-20261014",
    "representative-flow-independent-20261014/customer",
    "representative-flow-independent-20261014/management",
    "representative-flow-independent-20261014/ops",
)


def distribution(values):
    if not values:
        return None
    ordered = sorted(values)
    return {
        "n": len(ordered),
        "min": ordered[0],
        "p50": ordered[math.ceil(len(ordered) * 0.5) - 1],
        "p95": ordered[math.ceil(len(ordered) * 0.95) - 1],
        "max": ordered[-1],
    }


def summarize(root, name):
    directory = root / name
    hashes = {}

    def read(filename, lines=False, optional=False):
        path = directory / filename
        if optional and not path.exists():
            return None
        raw = path.read_bytes()
        hashes[filename] = hashlib.sha256(raw).hexdigest()
        if lines:
            return [json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()]
        return json.loads(raw)

    manifest = read("manifest.json")
    rows = read("attempts.jsonl", lines=True, optional=True) or []
    starts = read("provider-starts.jsonl", lines=True, optional=True)
    completions = read("provider-completions.jsonl", lines=True, optional=True)
    recalculation = read("recalculation.json", optional=True)
    if not manifest.get("sourceRevision"):
        raise ValueError(f"Missing source identity: {name}")
    groups = defaultdict(list)
    runs = {}
    for row in rows:
        result = row["result"]
        latency = result["latencyMillis"]
        if not isinstance(latency, int) or latency < 0:
            raise ValueError(f"Invalid observed duration: {name}")
        groups[(row["workload"], row["model"])].append(row)
        runtime = row.get("runtime")
        if runtime:
            run_id = runtime["runId"]
            previous = runs.get(run_id)
            if previous is None or row["runDurationMillis"] >= previous["runDurationMillis"]:
                runs[run_id] = row
    measurements = []
    for (workload, model), observations in sorted(groups.items()):
        values = [row["result"]["latencyMillis"] for row in observations]
        failures = Counter(row["result"]["failure"] for row in observations if row["result"].get("failure"))
        measurements.append({
            "workload": workload,
            "model": model,
            "recordedAttempts": len(observations),
            "latencyMillis": distribution(values),
            "latencyAbove30Seconds": sum(value > 30000 for value in values),
            "latencyAbove45Seconds": sum(value > 45000 for value in values),
            "timeoutRemoteUnknown": failures["TIMEOUT_REMOTE_UNKNOWN"],
            "failures": dict(sorted(failures.items())),
            "usageUnavailable": sum(row["result"]["usage"]["status"] != "measured" for row in observations),
        })
    missing_completion = None
    if starts is not None and completions is not None:
        start_ids = Counter(row["requestSha256"] for row in starts)
        complete_ids = Counter(row["requestSha256"] for row in completions)
        if complete_ids - start_ids:
            raise ValueError(f"Completion without observed start: {name}")
        missing_completion = sum((start_ids - complete_ids).values())
    run_observations = []
    for row in runs.values():
        budget = row["runtime"]["budget"]
        admitted = datetime.fromisoformat(budget["admittedAt"].replace("Z", "+00:00"))
        deadline = datetime.fromisoformat(budget["deadline"].replace("Z", "+00:00"))
        run_observations.append({
            "workload": row["workload"],
            "lastObservedDurationMillis": row["runDurationMillis"],
            "approvalWaitMillis": row["approvalWaitMillis"],
            "aboveRunDeadline": row["runDurationMillis"] > (deadline - admitted).total_seconds() * 1000,
            "execution": row["runtime"]["execution"],
            "failure": row["runtime"].get("failure"),
        })
    return {
        "cohort": name,
        "sourceRevision": manifest["sourceRevision"],
        "specification": manifest["specification"],
        "contextRevision": manifest.get("contextRevision"),
        "evidenceKind": manifest["evidenceKind"],
        "inputSha256": hashes,
        "recordedAttempts": len(rows),
        "observedStarts": None if starts is None else len(starts),
        "observedCompletions": None if completions is None else len(completions),
        "startsWithoutObservedCompletion": missing_completion,
        "originalRecalculationAvailable": recalculation is not None,
        "measurements": measurements,
        "runs": run_observations,
        "limitation": "Historical bounded samples; absent telemetry is unavailable, not zero. No production SLA or causal comparison.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-root", type=Path, default=Path("build"))
    parser.add_argument("--output", type=Path, default=Path("build/issue-156/latency-summary.json"))
    args = parser.parse_args()
    report = {"revision": "m7-lifetime-latency-v1", "quantiles": "nearest-rank",
              "cohorts": [summarize(args.input_root, name) for name in COHORTS]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for cohort in report["cohorts"]:
        print(f"{cohort['cohort']}: recorded={cohort['recordedAttempts']}, starts={cohort['observedStarts']}, "
              f"completions={cohort['observedCompletions']}")


if __name__ == "__main__":
    main()
