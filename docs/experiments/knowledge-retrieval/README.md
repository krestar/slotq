# #134 lexical / local embedding 비교와 재현

최신 remote main `9350efb4304fb103616d5ccaf941cbfa50a9d437`에서 #130/#131/#133과
#135 ownership을 확인하고 구현했다. [검색/security 계약](../../architecture/knowledge-retrieval.md)은
별도 문서에 둔다. 이 bounded comparison은 M8 generic evaluation 또는 production SLA가 아니다.

## Canonical 입력과 실행

기존 #133 `seed-manifest.json`의 안내/menu/operator 문서 3개와 두 synthetic manifest의 malicious
문서/다른 Tenant 안내를 사용한다. Tenant/Venue/Document/Version/Source/digest가 고정된 총 5개
문서이며 Customer PII가 없다. `retrieval-oracle.json`의 18 case를 두 candidate에 2회씩 실행한다.
Corpus는 실제 `CorpusIngestion`으로 MySQL 8.4에 publish한다. 각 case의 동일 canonical setup은
test-owned disposable DB에서만 재생성하며 production cleanup/withdraw tombstone을 우회하는
application 경로를 만들지 않는다. Publication change는 실제 authoring transaction으로 commit한다.

Case: Tenant A/B known source, Tenant B parameter/content substitution, operator visibility, missing
paraphrase, no-answer, partial allergy coverage, malicious instruction, superseded, withdrawn with vector
residue, query 중 update/withdraw, revoked/expired delegation, unavailable와 timeout이다. Wire/schema/
safe audit/현재 grant/observation 이후 withdrawal/한 composition의 네 tool/실제 SDK invocation은
동일 test class와 기존 Product tests가 별도로 검증한다.

Lexical은 lowercase Unicode term overlap과 고정 stopword set이다. 별도 preprocessing/index store가
없으며 준비 비용을 query에서 측정한다. Embedding은
[공식 MiniLM model](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)의 ONNX export,
revision `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`을 사용한다. ONNX와 tokenizer SHA-256을
preparation/inference에서 검증한다. Runtime은 [ONNX Runtime CPU API](https://onnxruntime.ai/docs/api/python/api_summary.html),
NumPy masked mean pooling와 L2 normalization, 384-dimensional cosine다. Random/hash/mock vector가
아니다. 문서 chunk는 480 character/stride 400, tokenizer max 256 wordpiece, CPU 1 thread다.
고정 relevance floor 0.35와 shared conservative excerpt coverage를 사용하며 oracle를 보고 floor를
튜닝하지 않았다. Cache는 최대 1024 exact-version chunks, 결과는 공통 3개(default)/최대 5개다.

Vector DB, external provider, 서버 daemon 또는 production Python dependency를 추가할 요구가 없어
test-only local process를 선택했다. Model process는 매 invocation 새로 시작하며 normalized document
vector cache는 candidate pass 사이에 유지한다. 두 pass는 isolated warm model server와 다르다.
Model import/load/hash 검증/startup 비용도 전체 query latency에 포함한다.

## Disclosure와 resource

공개 model/tokenizer preparation만 Hugging Face download를 호출한다. Query 실행은 로컬 파일과
CPU inference뿐이며 remote code loading, model download, external inference endpoint가 없다.
Document 입력은 query 직전 Auth/catalog가 승인한 해당 Tenant/Venue/visibility/current publication의
chunk다. Query 입력은 같은 live delegation 안에서 승인된 최대 512 character 문자열이다. 원문은
child stdin의 로컬 process memory로만 전달하며 별도 raw document/vector 파일을 만들지 않는다.
승인되지 않은 corpus를 model 또는 index query에 넣지 않는다. Model observation에는 exact
document/version scope와 invocation/token/CPU/RSS만 남기며 query는 synthetic raw evidence에서만
사용하고 MCP audit에는 넣지 않는다. 외부 tenant data disclosure/retention과 provider 과금은
해당하지 않는다. Host 운영비를 측정하지 않았으며 monetary cost=0으로 계상하지 않는다.

Raw는 candidate/query/repeat, oracle category, ranked source/version/score/480-character result,
실제 final metadata observation, failure, timestamps/query latency, Java CPU delta/heap sample와
child inference/load/CPU/RSS/Windows peak working set/model storage/vector cache bytes를 기록한다.
Java 수치는 같은 test JVM의 process/heap 관측이라 DB/다른 thread/JIT/GC noise가 섞이며 isolated
peak RSS가 아니다. Child CPU는 library import 이후 model load/inference delta이며 import/startup
CPU는 제외한다. Child RSS는 inference 후 sample이고 Windows peak working set은 해당 process의
peak 관측이다. Document/query embedding 시간을 따로 남기고 lexical은 별도 index build가 없어
query-time preparation에 포함한다. Provider
failed/timeout process의 successful inference/token 수는 미관측이며 성공 수에 합치지 않는다.

## 재현 명령

Repository root에서 Python 3.13과 JDK 25 / Docker를 준비한다. 실행 artifact는 모두 root/backend
`build/` 아래 gitignored 경로다. `backend clean`이 model/venv를 지우지 않도록 root build에 준비한다.

```powershell
python -m venv build/retrieval-venv
build/retrieval-venv/Scripts/python.exe -m pip install -r infra/retrieval/requirements.txt
build/retrieval-venv/Scripts/python.exe infra/retrieval/local_embedding.py prepare build/retrieval-model
python infra/retrieval/test_recalculation.py
Set-Location backend
$modelPython = (Resolve-Path ../build/retrieval-venv/Scripts/python.exe).Path
$modelPath = (Resolve-Path ../build/retrieval-model).Path
.\gradlew.bat test --tests 'com.slotq.mcp.*' --tests 'com.slotq.knowledge.*' --tests 'com.slotq.architecture.*' "-PknowledgePython=$modelPython" "-PknowledgeModel=$modelPath" --offline --no-daemon --console=plain
python ../infra/retrieval/recalculate.py build/retrieval-comparison/raw.json
.\gradlew.bat test "-PknowledgePython=$modelPython" "-PknowledgeModel=$modelPath" --offline --no-daemon --console=plain
.\gradlew.bat clean build "-PknowledgePython=$modelPython" "-PknowledgeModel=$modelPath" --offline --no-daemon --console=plain
python ../infra/retrieval/recalculate.py build/retrieval-comparison/raw.json
```

Comparison만 다시 실행하려면 다음 targeted command를 사용한다.

```powershell
.\gradlew.bat test --tests 'com.slotq.mcp.KnowledgeRetrievalIntegrationTests.comparisonRunsActualLexicalAndLocalModelOnTheSameCanonicalOracle' "-PknowledgePython=$modelPython" "-PknowledgeModel=$modelPath" --offline --no-daemon --console=plain --rerun-tasks
```

일반 test에서 model properties를 주지 않으면 실제 embedding comparison 한 case만 opt-in skip이다.
이를 실행 성공으로 표시하지 않는다. #134 완료 검증에서는 위 properties로 targeted/full/clean 모두
실제로 실행한다. Model/library 준비 실패, deadline 또는 unavailable를 mock 결과로 대체하지 않는다.
Raw provenance에는 canonical fixture digest, actual source hashes, environment, core library versions,
pinned model hashes가 있다. Summary recalculation은 canonical fixture/oracle digest와 exact source/
version/visibility/tenant, provider disclosure scope를 검사하며 altered/missing/duplicate evidence를
거부한다. Recalculation 자체의 5개 integrity test는 synthetic rows이며 model 실행 증거와 구분한다.

## 관측과 선택

2026-10-06 최종 targeted source의 actual comparison 관측은 다음과 같다. Java 25.0.4.1 /
Gradle 9.7.1 / Python 3.13.7 / Windows / AMD64 Family 25 Model 68 Stepping 1, 16 logical CPU,
약 15.2 GiB host RAM / disposable MySQL 8.4에서 측정했다. ONNX Runtime 1.23.2, NumPy 2.2.6,
tokenizers 0.22.2, psutil 7.2.2를 사용했다. 두 candidate는 같은 18-case oracle를 2회 실행했다.

| 실제 관측 | Lexical | MiniLM embedding |
| --- | --- | --- |
| Category oracle hit / 전체 case | 34 / 36 | 28 / 36 |
| Exact source/version hit / expected-source case | 12 / 14 | 8 / 14 |
| Tenant/visibility/lifecycle/source safety violation | 0 | 0 |
| Query latency min / median / max (ms) | 3.8 / 29.2 / 145.1 | 3.8 / 633.6 / 749.5 |
| Java process CPU delta 합 (shared JVM) | 0.72초 | 1.13초 |
| Java heap-used 최대 sample (shared JVM) | 224.2 MiB | 228.0 MiB |
| 별도 index build | 해당 없음; query-time term 준비 | 최초 model/index/query 658.4 ms; document inference 총 35.5 ms, vector cache write 총 0.8 ms |
| Provider invocation / 비용 | 해당 없음 | local successful batch 30회; document vector 6개/query vector 30개; external API 과금 해당 없음 |

Embedding child CPU 총 6.53초, 최대 RSS 약 155.4 MiB / Windows peak working set 약 196.7 MiB,
model/tokenizer/identity storage 약 86.7 MiB, exact vector cache 18,432 bytes를 관측했다. Query
inference 총 115.9 ms, model load 중앙값 210.4 ms이며 whole-query latency에는 process/import/
hash/metadata 비용이 더 들어간다. 2개의 withdrawal-residue prime query도 30회에 포함된다.
Expected unavailable/timeout의 실패 process는 successful inference/token 수에 합치지 않는다.
Lexical과 embedding 모두 `visiting hours` paraphrase를 놓쳤다. Embedding은 partial allergy와
operator source도 놓쳤고 `superseded` case에서 current replacement를 related/insufficient로
반환했다. 과거 superseded version을 반환한 것은 아니며 해당 quality failure를 숨기지 않았다.

최종 source에서 다음 검증을 완료했다. 모든 Backend 실행은 위 실제 model properties를 지정했다.

| 검증 | 실제 결과 |
| --- | --- |
| Related MCP/Knowledge/Architecture regression | 17 suite / 127 test, failure/error/skip 0; 5분 22초 PASS |
| 전체 Backend test | 97 suite / 803 test, 794 성공 / 기존 opt-in 9 skip, failure/error 0; 21분 25초 PASS |
| Backend clean build | 새 Boot JAR와 전체 test 재실행, 같은 97 suite / 803 test 결과; 21분 44초 PASS |
| Retrieval / architecture (위 실행에 포함) | Retrieval 15 test / architecture 6 suite·32 test, failure/error/skip 0 |
| Raw recalculation integrity | 5 test PASS, 실제 full/clean raw 재계산 PASS |

기존 skip은 capacity diagnostic 3개와 외부 Kafka secure/fault/evidence fixture 6개이며 성공으로
계상하지 않는다. Full/clean comparison의 source/category hit와 safety 결과는 위 표와 동일하다.
Full median은 lexical 28.0 ms / embedding 627.4 ms, clean median은 28.9 ms / 625.8 ms다.
Timing/resource는 run마다 달라질 수 있으며 상세 표는 targeted 실행 관측이다. Full/clean의
Java/test/harness source hash 46개가 동일하고 현재 파일과 일치한다. 새 Boot JAR에는 production
retrieval 클래스가 포함되고 test model/Python runtime은 포함되지 않았다. Frontend를 변경하지
않아 검증하지 않았고 GitHub CI 결과를 주장하지 않는다. 두 actual candidate의 source/version,
category와 latency/resource raw 결과를 위 command로 다시 계산할 수 있다. Default는 **lexical**이며
MiniLM candidate는 production adoption을 보류한다. 작은 English seed에서 model은 lexical 대비
source/category 이득을 보이지 않았고 model/process/resource 비용을 추가했다. Semantic paraphrase
누락, allergy partial-source 누락, operator source 누락과 current replacement의 관련도 오탐을
관측했다. 이 oracle의 어떤 safety 위반도 quality/latency로 상쇄하지 않는다.

Production lexical에는 paraphrase/stemming/한국어 품질 보장, semantic entailment/정답 생성,
범용 allergy 판단이 없다. 5-document/18-query/2-pass 결과를 production accuracy나 load/SLA로
일반화하지 않는다. No-answer는 현재 검색 방식의 bounded 결과이며 전체 지식의 부재 증명은 아니다.
Future candidate/model/chunk/threshold 변경은 같은 corpus/oracle의 actual comparison과 safety
verification이 필요하다. Hybrid/복수 provider/Router/Agent Runtime/M8 평가 플랫폼은 도입하지 않는다.
