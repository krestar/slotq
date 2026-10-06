"""Summary integrity checks; these synthetic rows are not model comparison evidence."""
import copy
import hashlib
import json
from pathlib import Path
import unittest
from recalculate import recalculate


class RecalculationTests(unittest.TestCase):
    def fixture(self):
        root = Path(__file__).resolve().parents[2] / "backend/src/test/resources/knowledge"
        queries = json.loads((root / "retrieval-oracle.json").read_text())["queries"]
        documents = []
        for name in ("seed-manifest.json", "retrieval-extra-manifest.json", "retrieval-other-tenant-manifest.json"):
            manifest = json.loads((root / name).read_text())
            documents.extend((manifest, d) for d in manifest["documents"])
        rows = []
        for candidate in ("lexical", "embedding"):
            for repeat in (0, 1):
                for query in queries:
                    hits = []
                    allowed = []
                    tenant = "10000000-0000-0000-0000-000000000001"
                    venue = "20000000-0000-0000-0000-000000000001"
                    if "document" in query:
                        manifest, document = next((m, d) for m, d in documents if d["documentId"] == query["document"])
                        tenant, venue = manifest["tenantId"], manifest["venueId"]
                        source = {"sourceId": document["sourceId"], "reference": document["sourceReference"], "title": document["sourceTitle"]}
                        allowed = [{"reference": {"document": {"documentId": document["documentId"], "tenantId": {"value": tenant}, "venueId": {"value": venue}},
                                                  "versionId": document["versionId"], "visibility": document["visibility"]}, "source": source}]
                        hits = [{"documentId": document["documentId"], "versionId": document["versionId"], "sourceId": document["sourceId"],
                                 "sourceReference": document["sourceReference"], "sourceTitle": document["sourceTitle"], "visibility": document["visibility"]}]
                    rows.append({"candidate": candidate, "repeat": repeat, "queryId": query["id"], "query": query["query"], "scenario": query["scenario"],
                                 "expectedCategory": query["category"], "expectedDocument": query.get("document", ""), "queryMillis": 1,
                                 "javaCpuSeconds": 0.1, "javaHeapUsedBytes": 1000, "trustedTenant": tenant, "trustedVenue": venue,
                                 "allowedAtFinal": allowed, "result": {"category": query["category"], "results": hits}})
        observation = {"documentDisclosure": [], "trustedTenant": tenant, "trustedVenue": venue, "operatorAllowed": False,
                       "newDocumentEmbeddings": 1, "queryEmbeddings": 1, "invocations": 2, "tokens": 20, "cpuSeconds": 0.1,
                       "rssBytes": 100, "peakWorkingSetBytes": 200, "indexBytes": 3072, "indexAndQueryMillis": 10,
                       "documentEmbeddingMillis": 2, "cacheWriteMillis": 0.1, "queryEmbeddingMillis": 1, "loadMillis": 5, "inferenceMillis": 3, "externalCalls": 0}
        return {"provenance": {"fixtureSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in root.glob("*.json")}, "modelStorageBytes": 100},
                "rows": rows, "providerObservations": [observation]}

    def test_same_oracle_recalculates_exact_counts(self):
        result = recalculate(self.fixture())
        self.assertEqual(result["lexical"]["categoryHits"], 36)
        self.assertEqual(result["embedding"]["sourceVersionHits"], 14)
        self.assertEqual(result["lexical"]["safetyViolations"], 0)

    def test_source_version_tenant_and_visibility_substitution_is_not_hidden_by_quality(self):
        for field in ("sourceId", "versionId", "visibility"):
            raw = self.fixture()
            raw["rows"][0]["result"]["results"][0][field] = "substituted"
            self.assertEqual(recalculate(raw)["lexical"]["safetyViolations"], 1)
        raw = self.fixture()
        raw["rows"][0]["trustedTenant"] = "substituted"
        self.assertEqual(recalculate(raw)["lexical"]["safetyViolations"], 1)

    def test_changed_oracle_digest_duplicate_and_missing_rows_are_rejected(self):
        for mutation in (lambda r: r["rows"].pop(), lambda r: r["rows"][0].update(queryId="unknown"),
                         lambda r: r["rows"][0].update(expectedCategory="no_answer"),
                         lambda r: r["provenance"]["fixtureSha256"].update({"seed-manifest.json": "changed"}),
                         lambda r: r["rows"].__setitem__(0, copy.deepcopy(r["rows"][1]))):
            raw = self.fixture(); mutation(raw)
            with self.assertRaises(ValueError):
                recalculate(raw)

    def test_pre_query_model_disclosure_scope_is_verified(self):
        raw = self.fixture()
        raw["providerObservations"][0]["documentDisclosure"] = [{"document": {"tenantId": {"value": "other"}, "venueId": {"value": raw["providerObservations"][0]["trustedVenue"]}}, "visibility": "VENUE_PUBLIC"}]
        with self.assertRaises(ValueError):
            recalculate(raw)

    def test_unsupported_peak_resource_sample_is_unavailable_not_zero(self):
        raw = self.fixture()
        raw["providerObservations"][0]["peakWorkingSetBytes"] = None
        self.assertEqual(recalculate(raw)["localModel"]["maxPeakWorkingSetBytes"], "unavailable")


if __name__ == "__main__":
    unittest.main()
