"""Issue #134 bounded oracle recalculation from fresh raw output; no inference calls."""
import json
from pathlib import Path
import statistics
import sys
import hashlib
import math


def recalculate(raw):
    fixture_root = Path(__file__).resolve().parents[2] / "backend/src/test/resources/knowledge"
    oracle = json.loads((fixture_root / "retrieval-oracle.json").read_text(encoding="utf-8"))
    queries = {q["id"]: q for q in oracle["queries"]}
    for name, digest in raw["provenance"]["fixtureSha256"].items():
        if name not in ("seed-manifest.json", "retrieval-extra-manifest.json", "retrieval-other-tenant-manifest.json", "retrieval-oracle.json") or hashlib.sha256((fixture_root / name).read_bytes()).hexdigest() != digest:
            raise ValueError("canonical fixture digest")
    if len(raw["provenance"]["fixtureSha256"]) != 4:
        raise ValueError("missing fixture identity")
    if len(raw["rows"]) != 72 or not raw["providerObservations"]:
        raise ValueError("incomplete actual comparison")
    summaries = {}
    for candidate in ("lexical", "embedding"):
        rows = [r for r in raw["rows"] if r["candidate"] == candidate]
        if len(rows) != 36 or len({(r["queryId"], r["repeat"]) for r in rows}) != 36:
            raise ValueError("oracle denominator")
        category_hits = source_hits = source_denominator = 0
        safety = []
        failures = []
        for row in rows:
            expected = queries.get(row["queryId"])
            if expected is None or row["repeat"] not in (0, 1) or row["expectedCategory"] != expected["category"] or row["query"] != expected["query"] or row["scenario"] != expected["scenario"] or row["expectedDocument"] != expected.get("document", "") or not math.isfinite(row["queryMillis"]) or row["queryMillis"] < 0:
                raise ValueError("canonical oracle mismatch")
            result = row["result"]
            if len(result["results"]) > 5 or result["category"] not in ("evidence", "no_answer", "insufficient", "unavailable", "denied") or result["category"] in ("no_answer", "unavailable", "denied") and result["results"]:
                raise ValueError("bounded result contract")
            category_hits += result["category"] == row["expectedCategory"]
            allowed = {(v["reference"]["document"]["documentId"], v["reference"]["versionId"]): v for v in row["allowedAtFinal"]}
            for hit in result["results"]:
                key = (hit["documentId"], hit["versionId"])
                exact = allowed.get(key)
                if exact is None or exact["reference"]["visibility"] != hit["visibility"] or exact["reference"]["document"]["tenantId"]["value"] != row["trustedTenant"] or exact["reference"]["document"]["venueId"]["value"] != row["trustedVenue"] or exact["source"]["sourceId"] != hit["sourceId"] or exact["source"]["reference"] != hit["sourceReference"] or exact["source"]["title"] != hit["sourceTitle"]:
                    safety.append({"queryId": row["queryId"], "repeat": row["repeat"], "hit": key})
            exact_hit = True
            if row["expectedDocument"]:
                source_denominator += 1
                # Exact version, source identity and source hit are checked separately from category correctness.
                exact_hit = any(h["documentId"] == row["expectedDocument"] and h["versionId"] == expected["versionId"] and h["sourceId"] == expected["sourceId"] and (h["documentId"], h["versionId"]) in allowed for h in result["results"])
                source_hits += exact_hit
            if result["category"] != row["expectedCategory"] or not exact_hit:
                failures.append({"queryId": row["queryId"], "repeat": row["repeat"], "category": result["category"], "expected": row["expectedCategory"]})
        latencies = sorted(r["queryMillis"] for r in rows)
        summaries[candidate] = {"rows": len(rows), "categoryHits": category_hits, "sourceVersionHits": source_hits,
                                "sourceDenominator": source_denominator, "safetyViolations": len(safety),
                                "safetyFailures": safety, "qualityFailures": failures,
                                "queryMillis": {"min": min(latencies), "median": statistics.median(latencies), "max": max(latencies)},
                                "javaCpuSeconds": sum(r["javaCpuSeconds"] for r in rows),
                                "javaHeapUsedMaxBytes": max(r["javaHeapUsedBytes"] for r in rows),
                                "firstPassMedianMillis": statistics.median(r["queryMillis"] for r in rows if r["repeat"] == 0),
                                "secondPassMedianMillis": statistics.median(r["queryMillis"] for r in rows if r["repeat"] == 1)}
    observations = raw["providerObservations"]
    for observation in observations:
        for reference in observation["documentDisclosure"]:
            if reference["document"]["tenantId"]["value"] != observation["trustedTenant"] or reference["document"]["venueId"]["value"] != observation["trustedVenue"] or reference["visibility"] == "VENUE_OPERATOR" and not observation["operatorAllowed"]:
                raise ValueError("pre-query disclosure isolation violation")
    summaries["localModel"] = {"successfulBatchProcesses": len(observations),
                               "documentEmbeddings": sum(o["newDocumentEmbeddings"] for o in observations),
                               "queryEmbeddings": sum(o["queryEmbeddings"] for o in observations),
                               "textInvocations": sum(o["invocations"] for o in observations),
                               "tokens": sum(o["tokens"] for o in observations),
                               "nativeCpuSeconds": sum(o["cpuSeconds"] for o in observations),
                               "maxRssBytes": max(o["rssBytes"] for o in observations),
                               "maxPeakWorkingSetBytes": max((o["peakWorkingSetBytes"] for o in observations if o["peakWorkingSetBytes"] is not None), default="unavailable"),
                               "maxVectorBytes": max(o["indexBytes"] for o in observations),
                               "modelStorageBytes": raw["provenance"]["modelStorageBytes"],
                               "firstIndexAndQueryMillis": observations[0]["indexAndQueryMillis"],
                               "documentEmbeddingMillisTotal": sum(o["documentEmbeddingMillis"] for o in observations),
                               "cacheWriteMillisTotal": sum(o["cacheWriteMillis"] for o in observations),
                               "queryEmbeddingMillisTotal": sum(o["queryEmbeddingMillis"] for o in observations),
                               "loadMillisMedian": statistics.median(o["loadMillis"] for o in observations),
                               "inferenceMillisTotal": sum(o["inferenceMillis"] for o in observations),
                               "externalCalls": sum(o["externalCalls"] for o in observations),
                               "providerCost": "not_applicable_local_CPU"}
    return summaries


if __name__ == "__main__":
    path = Path(sys.argv[1])
    summary = recalculate(json.loads(path.read_text(encoding="utf-8")))
    encoded = json.dumps(summary, indent=2)
    path.with_name("summary.json").write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    if summary["lexical"]["safetyViolations"] or summary["embedding"]["safetyViolations"]:
        sys.exit(1)
