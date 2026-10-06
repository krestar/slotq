# Tenant 출처 검색과 MCP integration

Issue [#134](https://github.com/krestar/slotq/issues/134)의 구현 계약이다.
[ADR-0009](../adr/0009-m6-authenticated-access-and-knowledge.md),
[M6](m6-access-knowledge.md), [Corpus authority](knowledge-corpus.md)를 따른다.

## Ownership와 scope

Concrete retrieval은 `integration/mcp/knowledge`에 있다. Knowledge authority는 Auth/active
Venue 공개 port와 자신의 persistence만 의존하며 MCP, embedding, vector runtime을 의존하지
않는다. 기존 architecture guard를 유지하고 concrete retrieval의 직접 SQL/JPA/Product
persistence 접근도 검사한다. Product 도메인, transaction, confirmation, idempotency는 변경하지 않는다.

`CorpusCatalog.publications`는 내부 delegation ID와 target Venue만 받는다. 매 호출 live Auth
delegation, active Venue와 server-derived Tenant를 확인한다. Current publication joined SQL의
WHERE에 Tenant/Venue와 허용 visibility를 넣은 뒤 immutable payload를 읽는다. Lexical/vector
query 또는 model 입력 전에 이 set을 확정한다. Customer와 public-only management는 public만,
operator-only management는 operator만, 둘을 승인한 management는 두 visibility를 읽는다.
Owner/assigned Manager/Staff의 current permission 판정은 기존 Auth/catalog가 소유한다.

Complete corpus bound는 Venue/visibility당 64개 document다. 65번째를 발견하면 safe unavailable로
실패하며 일부만 검색한 결과를 complete/no-answer로 표시하지 않는다. Caller schema에는 Tenant,
Venue, role, principal, provider URL, document selector 또는 visibility claim이 없다.

## Candidate와 최종 observation

Production default는 bounded lexical term-overlap이다. Lowercase Unicode term set과 고정 stopword
set을 사용하며 stemming/synonym expansion은 없다. Score는 query term coverage다. 매 query에서
이미 제한된 current set을 처리하므로 별도 persistent index/build/re-index queue가 없다.
이 준비 비용은 query latency에 포함된다.

Comparison candidate는 로컬 MiniLM ONNX CPU inference와 bounded process-local exact-version
vector cache다. `CandidateSearch`는 catalog가 승인한 published version set, query, deadline만
받으며 publication write 권한이 없다. Cache key는 exact Tenant/Venue/Document/Version/digest/
visibility와 chunk offset이다. Cache 전체를 검색하지 않고 해당 query에 승인된 exact key만
선택해 cosine query를 실행한다. New document/query vector는 실제 model inference로 생성한다.
Old version 작업이 늦게 완료되어 vector residue가 남아도 다음 query의 approved key set에
없으면 scoring 대상이 아니며 current metadata를 바꾸는 경로도 없다.

Candidate가 반환한 reference는 최초 approved set에 속해야 한다. 다른 identity 또는 stale
residue를 잘못 반환하는 candidate는 unavailable로 거부한다. Approved candidate마다 응답
excerpt 구성 직전에 `CorpusCatalog.revalidateExact`를 호출한다. Catalog는 outer RR snapshot을
사용하지 않는 새 READ_COMMITTED transaction의 exact joined read다. Superseded/withdrawn/
visibility-changed version은 제거하며 새 version으로 relabel하지 않는다. Excerpt는 이 read가
반환한 immutable payload의 최대 480 character window에서 구성한다.

Observation point는 **각 반환 source의 materialization 직전 exact current metadata read**다.
Query 중 update/withdraw를 candidate 생성 후 이 read 전에 commit하면 old payload가 제거된다.
이 read 이후 withdrawal까지 이미 관찰한 결과에서 소급 제거하지 않는다. 여러 source를 하나의
atomic publication snapshot으로 약속하지 않는다. 마지막 응답 전에 live delegation/deadline을
다시 확인하며 그 확인 이후 grant 변경도 소급 취소를 약속하지 않는다. Provider disclosure의
eligibility observation은 입력 직전 scoped publication read이며 이후 변경을 과거 disclosure에서
취소할 수 있다는 주장은 없다. External provider는 구현/채택하지 않았다.

## 결과와 failure 의미

`knowledge.search` 입력은 nonblank query 최대 512 character, optional integer limit 1~5
(default 3)이다. Output은 closed schema의 category/reason/results, 최대 5개의 Source/Document/
Version/visibility/rank/finite score와 최대 480 character untrusted excerpt다. Source reference는
provenance이며 URL fetch, Product truth 또는 권한 증거가 아니다.

| Category | 구현 의미 |
| --- | --- |
| `evidence` | Returned current excerpts가 query의 informative lexical term을 모두 포함. 검색 coverage 표시이며 문장 entailment, allergy guarantee 또는 Product policy 판정이 아님 |
| `insufficient` | Candidate가 관련 source를 찾았지만 returned excerpt의 보수적 term coverage가 부족. 추측/answer generation 없음 |
| `no_answer` | Scoped search와 최종 eligibility 검사를 완료했지만 반환할 source 없음. 검색 방식의 한계 때문에 관련 paraphrase도 놓칠 수 있음 |
| `unavailable` | Authority/index/provider/deadline failure 또는 corpus/candidate bound 위반. Empty no-answer로 해석하지 않음 |
| `denied` | Live access 또는 semantic input 검증 거부. Common schema/admission 오류는 기존 MCP safe error envelope 사용 |

이 coverage 규칙과 embedding cosine floor 0.35는 작은 comparison의 고정 설정이며 production
accuracy/SLA threshold가 아니다. Canonical labelled oracle로 coverage/missing-source 결과를
별도 평가한다. Coverage가 좋아도 scope/publication/source 안전 위반을 상쇄하지 않는다.

## 공통 MCP 경로

기존 `ProductToolConfiguration`의 한 explicit `McpRegistrations` bean에서 Product 세 tool과
knowledge tool을 함께 compose한다. Competing registration bean/component discovery는 없다.
기존 registry/schema, admission, delegated Auth, resource quota, worker/deadline, audit 경로를
그대로 사용한다. Server registration은 management public/operator의 대안 action을 명시하며
catalog가 실제 visibility/current access를 강제한다. Tool enumeration과 direct invocation은
같은 permission을 검사한다.

Knowledge registration만 timeout/interrupt/unhandled failure disposition을 unavailable로 지정한다.
기존 registration의 default는 unknown이며 Product mutation의 ambiguity를 바꾸지 않는다.
Retrieval budget은 남은 MCP budget과 10초 중 짧은 값이다. Timeout 뒤 handler/provider가 살아
있으면 기존 worker/quota permit은 실제 종료까지 유지한다. Global/cross-tenant fallback, retry,
queue를 추가하지 않는다. Comparison child는 timeout에 종료하고 actual process exit까지 기다린다.

Audit는 반환한 최대 5개의 source/document/version UUID와 기존 trusted Actor/scope/request
metadata만 연결한다. Query, excerpt, source URL/title, raw document/provider/SQL error는 넣지
않는다. Primary document/version reference도 기존 field에 연결한다. Best-effort sink 실패는
검색/기존 Product 결과를 바꾸지 않는다. Malicious excerpt는 `untrusted=true` 데이터이며
registry permission 또는 HOLD approval/confirmation을 생성할 수 없다.

## 실행과 범위

Synthetic fixtures와 actual local model의 재현·비교·선택 근거는
[comparison runbook](../experiments/knowledge-retrieval/README.md)을 따른다. Raw JSON/summary/log는
gitignored build에만 생성한다. Canonical MySQL tests는 pre-query scope, stale/withdrawn residue,
query 중 publication change 전/후, expired/revoked/current grant, schema/output/audit, unavailable/
timeout와 실제 SDK HTTPS 호출을 검사한다. 기존 Product HTTPS/MySQL regression도 네 production
tool이 동일 composition에서 호출되는 것을 확인한다. #135는 이 경로의 fresh integration/closure를
소유하며 이 구현으로 M6 전체 종료를 선언하지 않는다. Frontend, retrieval HTTP API, answer
generation, Agent Runtime, Router, async workflow 또는 신규 infrastructure는 없다.
