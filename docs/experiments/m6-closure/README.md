# M6 / #135 통합 종료 검증

작업 시작 시 remote main을 fetch해 `282b3097877e517cd11dd665e06e46c28cd02c7e`를 확인했다.
#135 본문의 `688c1a5`는 설계 당시 historical revision이며 실행 기준으로 checkout하지 않았다.
#130/PR #136, #131/PR #137, #132/PR #138, #133/PR #143, #134/PR #144의 merged 상태와
현재 source를 직접 대조했고 `cc5ae0f`/`e380686`의 evidence cleanup 정책도 확인했다.
중단 후 재개 시에도 remote main을 다시 fetch해 같은 HEAD를 확인했다.

## Inventory와 fresh integration 공백

Primary contract는 [ADR-0009](../../adr/0009-m6-authenticated-access-and-knowledge.md),
[M6 접근 계약](../../architecture/m6-access-knowledge.md),
[corpus](../../architecture/knowledge-corpus.md),
[retrieval](../../architecture/knowledge-retrieval.md)이다. #131~#134 Issue 완료조건과 현재 source/test를
대조하고 아래 owner/재현 경로를 inventory했다. 기존 test 성공만으로 통합 종료를 추론하지 않는다.

기존 Product test는 HTTPS HOLD/own GET/management list와 별도 knowledge delegation 호출을,
retrieval test는 실제 SDK 검색과 candidate/catalog lifecycle을 검증한다. #135는 같은 production
composition에서 Customer/management/Tenant A/B 동시 세션, publication 변경 중 Product 조회,
COMMIT 후 response loss와 profile별 reconciliation, 공통 audit correlation을 함께 검증한다.
별도 실제 low-rate instance는 denial/schema failure의 quota 소비와 session/delegation/principal/
tool 변경 우회를 검증한다. Production code, migration, auth/confirmation/corpus 정책은 바꾸지 않는다.
선택된 protocol은 MCP `2025-11-25`, HTTPS Streamable HTTP, initialize/initialized와 tools-only다.
Auth는 controlled client의 opaque bearer와 original Actor에서 좁힌 delegation을 유지한다.

Canonical reference (아래 약칭은 재실행 가능한 test source):

- **I**: [M6IntegrationTests](../../../backend/src/test/java/com/slotq/mcp/M6IntegrationTests.java), 신규 9 case.
- **Q**: [M6RateLimitIntegrationTests](../../../backend/src/test/java/com/slotq/mcp/M6RateLimitIntegrationTests.java), 신규 1 case.
- **H**: [McpHttpIntegrationTests](../../../backend/src/test/java/com/slotq/mcp/McpHttpIntegrationTests.java).
- **F**: [McpFoundationTests](../../../backend/src/test/java/com/slotq/mcp/McpFoundationTests.java),
  [accounting](../../../backend/src/test/java/com/slotq/mcp/McpAccountingTests.java),
  [engine concurrency](../../../backend/src/test/java/com/slotq/mcp/McpEngineConcurrencyTests.java).
- **P**: [ProductToolsIntegrationTests](../../../backend/src/test/java/com/slotq/mcp/ProductToolsIntegrationTests.java).
- **B**: [ProductAdmissionGateDiagnosticTests](../../../backend/src/test/java/com/slotq/mcp/ProductAdmissionGateDiagnosticTests.java),
  [DB bounds](../../../backend/src/test/java/com/slotq/mcp/ProductDatabaseBoundsIntegrationTests.java),
  [write activation](../../../backend/src/test/java/com/slotq/mcp/ProductWriteActivationTests.java).
- **C**: [CorpusIntegrationTests](../../../backend/src/test/java/com/slotq/knowledge/CorpusIntegrationTests.java).
- **R**: [KnowledgeRetrievalIntegrationTests](../../../backend/src/test/java/com/slotq/mcp/KnowledgeRetrievalIntegrationTests.java).
- **A**: [MCP architecture](../../../backend/src/test/java/com/slotq/architecture/McpArchitectureTests.java),
  [knowledge architecture](../../../backend/src/test/java/com/slotq/architecture/KnowledgeArchitectureTests.java),
  repository dependency/port/ownership guards.
- **D**: [ReservationHoldIntegrationTests](../../../backend/src/test/java/com/slotq/ReservationHoldIntegrationTests.java),
  [ManagementApiIntegrationTests](../../../backend/src/test/java/com/slotq/ManagementApiIntegrationTests.java),
  기존 Product role/domain/transaction/retention regression.

## Criterion → owner → verification → reference → revision → limitation

최종 Backend 검증 revision **V**는 `ae21e8b6579ac1bb3fabcb00001dd1282880bd94`다.
최신 main에 신규 I/Q test만 추가한 commit이며 이후 Backend source 변경은 없다.
**T/W/K**는 아래 targeted/전체 test/clean build 명령이다. 모든 행은 이 revision의 해당 경로와
제약을 연결한다. Historical test source를 재사용해도 과거 PR의 PASS 수치를 fresh 결과로 옮기지 않는다.

| Criterion | Primary owner | Executed verification / reference | Revision | 상태 | Limitation |
| --- | --- | --- | --- | --- | --- |
| 실제 lifecycle/list/call, 두 profile, 네 tool | #131/#132/#134, 통합 #135 | I `sdkProfilesUseAllFourProductionToolsConcurrentlyWithExactAuditAndDurableState`; H negotiation/session | V / T,W,K | FRESH PASS | MCP 2025-11-25 / SDK 2.0.1 / tools-only / controlled opaque bearer |
| Caller principal/tenant/role/allowlist injection | #131/#132/#134 | I `maliciousContentCannotGrantHiddenToolsConfirmationOrCallerAuthority`; P strict schema; H | V / T,W,K | FRESH PASS | Claim 기반 provisioning 공개 endpoint 없음 |
| Expiry/revoke/current authority 축소 | #131 | I `currentGrantReductionExpiryAndRevocationApplyAcrossToolsAndAfterRetrievalAdmission`; H current authority; C lock 후 재검증 | V / T,W,K | FRESH PASS | 판정 이후 변경의 Product transaction 소급 rollback 없음 |
| M5 recovery/scrape와 audience 분리 | #131/#133 | I `validRecoveryScrapeOriginalAndProductCredentialsCannotBecomeMcpAuthority`; H/C audience | V / T,W,K | FRESH PASS | M5 credential의 AI 전용 없음 |
| Concurrent principal/profile/context isolation | #131, 통합 #135 | I SDK concurrent A/B/management + audit identity; F immutable context | V / T,W,K | FRESH PASS | 단일 live instance |
| Enumeration과 forbidden direct invocation | #131/#132/#134 | I SDK list/hidden direct call/session substitution; H/F 독립 authorization | V / T,W,K | FRESH PASS | Management DTO allowedActions는 tool grant 아님 |
| Strict schema/unknown extra/type/bounds | #131/#132/#134 | I injection; P schemas; R strict input/output/excerpt bound | V / T,W,K | FRESH PASS | 미등록 tool은 protocol error |
| Tenant/Venue/Slot/Reservation target substitution | #132/#133/#134 | I cross-tenant get/confirmation; P exact approval/Product material; C scoped FK/authoring; R pre-query scope | V / T,W,K | FRESH PASS | Scope는 server-derived |
| Authenticated Product HTTP / own-only / 관리 grant | #131/#132 | I TLS four tools/durable rows; P OWNER/MANAGER/STAFF/own-only; A dependency guards | V / T,W,K | FRESH PASS | Adapter에서 Product domain/authorization 복제 없음 |
| Transaction/locking/capacity/Product idempotency | Product M2/#132 | P conflict/Slot lock/outage; ReservationHoldIntegrationTests same-key/rollback/retention; I durable three rows | V / T,W,K | FRESH PASS | MCP의 reliability table lookup 없음 |
| Exact confirmation substitution/replay/immutable intent | #132 | I `confirmationSubstitutionAndConcurrentReplayAcrossKnowledgeCallsKeepOneProductKey`; P exact binding | V / T,W,K | FRESH PASS | Approval은 controlled original-Actor adapter |
| Same-key concurrent HOLD | #132/Product M2 | I concurrent replay; P replay; ReservationHoldIntegrationTests concurrency | V / T,W,K | FRESH PASS | Reservation/Allocation/reliability 각 1건 |
| COMMIT 후 response loss / same-intent key | #132 | I `committedResponseLossReconcilesAcrossProfilesWithoutInventingUnknownTargetFailure`; P response faults | V / T,W,K | FRESH PASS | 실제 controller/COMMIT 후 Tomcat partial response/close |
| Known-target reconciliation / unknown 보존 | #132 | I known/unknown Location, random GET 404, management list, 명시 retry; P exact GET | V / T,W,K | FRESH PASS | Unknown을 임의 failure로 확정하지 않음; 새 lookup API 없음 |
| completedAt+24h / renewal 비연장 | #132/Product M2 | I completedAt 불변/24h approval 거부; P fresh intent의 old key 거부; ReservationHoldIntegrationTests 24h 전/경계/후 | V / T,W,K | FRESH PASS | Retry는 최초 dispatch+15분, confirmation 최대 5분 |
| Bounded/delayed admission activation | #131/#132 | B 실제 Product HTTP post-security/pre-controller 61초 delay, DB bounds/unsafe profile; P continuing Product accounting | V / T,W,K | FRESH PASS | B는 diagnostic registry fixture; 단일 host/no reconnect/no queue/redelivery; 일반 deadline protocol 없음 |
| Product truth와 Knowledge authority 분리 | #130/#132/#133/#134 | I publication mutation 중 Product identity 유지; A upstream dependency/persistence 금지 | V / T,W,K | FRESH PASS | Natural-language content는 Product policy를 변경하지 않음 |
| Provider/query 이전 Tenant/Venue/visibility isolation | #133/#134 | I Customer/management/A/B source; R `knownAnswerAndSqlScopeAreEnforcedBeforeCandidateAndModelInputs`; actual comparison disclosure | V / T,W,K | FRESH PASS | External inference provider 없음 |
| Immutable identity/source/version attribution | #133/#134 | I exact source audit; C immutable source/content/visibility; R provenance bounds | V / T,W,K | FRESH PASS | UUID-only audit; bounded untrusted excerpt |
| Superseded/withdrawn/stale index/old re-index | #133/#134 | I `publicationChangesDuringWireQueryAndLateOldReindexDoNotChangeProductTruth`; C re-index races; R residue/actual model comparison | V / T,W,K | FRESH PASS | Lexical은 persistent index 없음; model cache는 test-only derived |
| Query 중 version change / final metadata observation | #133/#134 | I paused scoped snapshot + 실제 publish/withdraw; R observation 전/후; C outer RR stale 배제 | V / T,W,K | FRESH PASS | Source별 final current read; multi-source atomic snapshot/소급 취소 없음 |
| Malicious document → forbidden invocation | #131/#132/#134 | I malicious retrieval 후 management direct call/confirmation injection; R deterministic attack | V / T,W,K | FRESH PASS | Agent Runtime 없음 |
| no-answer/insufficient/unavailable/provider timeout | #134 | I `metadataUnavailableIsSafeAndDistinctFromNoAnswerAndInsufficient`; R provider exception/deadline + actual comparison timeout | V / T,W,K | FRESH PASS | Metadata fault와 actual local provider fault 구분; global fallback 없음 |
| Timeout/resource accounting/cancel/disconnect | #131/#132/#134 | I `continuingRetrievalTimeoutConsumesSharedCapacityUntilActualExitWhileProductStillWorks`; P server continuing work; H/F interrupt/timeout | V / T,W,K | FRESH PASS | Timeout은 종료/rollback 증거가 아님 |
| Rate-limit bypass/denial/retry 소비 | #131 | Q session/delegation/principal/tool 변경; F no refund/cardinality/clock regression | V / T,W,K | FRESH PASS | 단일 instance local quota, restart reset 가능 |
| Shared quota / instance-crossing | #130/#131 | ADR-0009 / runbook / activation source 대조 | 282b309 + 본 PR docs | UNSUPPORTED | Shared topology 미선택; Redis/global 보장 없음 |
| Audit credential/PII/raw query/document/provider-error redaction | #131/#132/#134 | I actual metadata events + captured output sentinels; H/P/R redaction; F queue/sink failure | V / T,W,K | FRESH PASS | Best effort metadata audit, durable business ledger 아님 |
| Success/deny/unknown/source correlation | #131/#132/#134, 통합 #135 | I request/principal/delegation/scope/Product request/intent/confirmation/source/version mapping | V / T,W,K | FRESH PASS | Transport/auth 거부 전체의 durable audit를 주장하지 않음 |
| Actual lexical/embedding / 동일 corpus/query/oracle / 재계산 | #134 | R `comparisonRunsActualLexicalAndLocalModelOnTheSameCanonicalOracle`; infra/retrieval/test_recalculation.py + recalculate.py | V / T,W,K | FRESH PASS | 5문서/18 query/2 pass synthetic English, production SLA 아님 |
| Roadmap 종료조건/owner/알려진 제약 | #135 | 위 criterion, roadmap M6, 최종 결과/limitation 대조 | 본 PR | FRESH PASS | M7/M8 미착수 |

## 실행 명령과 최종 결과

JDK 25 / Docker / pinned actual model 준비는 [#134 재현 명령](../knowledge-retrieval/README.md)을 따른다.
Root `build/`의 Python/model은 `backend clean`으로 제거되지 않는다. 아래는 실제 실행 명령이며
model properties를 모든 final Backend 명령에 지정한다. PowerShell에서 `backend/` 기준이다.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
$modelPython=(Resolve-Path ../build/retrieval-venv/Scripts/python.exe).Path
$modelPath=(Resolve-Path ../build/retrieval-model).Path
# I/Q standalone
.\gradlew.bat test --tests 'com.slotq.mcp.M6*' --offline --no-daemon --console=plain
# T: targeted integration + owner security/Product/architecture regression + actual comparison
.\gradlew.bat test --tests 'com.slotq.mcp.*' --tests 'com.slotq.knowledge.*' --tests 'com.slotq.architecture.*' --tests com.slotq.ReservationHoldIntegrationTests --tests com.slotq.ManagementApiIntegrationTests --tests com.slotq.HoldIdempotencyScopeMigrationTests --tests com.slotq.SlotqApplicationTests "-PknowledgePython=$modelPython" "-PknowledgeModel=$modelPath" --offline --no-daemon --console=plain
../build/retrieval-venv/Scripts/python.exe ../infra/retrieval/test_recalculation.py
../build/retrieval-venv/Scripts/python.exe ../infra/retrieval/recalculate.py build/retrieval-comparison/raw.json
# W: full Backend test
.\gradlew.bat test "-PknowledgePython=$modelPython" "-PknowledgeModel=$modelPath" --offline --no-daemon --console=plain --info
# K: clean build + full test rerun
.\gradlew.bat clean build "-PknowledgePython=$modelPython" "-PknowledgeModel=$modelPath" --offline --no-daemon --console=plain --info
../build/retrieval-venv/Scripts/python.exe ../infra/retrieval/recalculate.py build/retrieval-comparison/raw.json
```

이번 fresh execution은 2026-10-06에 Temurin 25.0.4.1+1 / Gradle 9.7.1 / Windows /
disposable MySQL 8.4에서 수행했다.
신규 I/Q standalone은 2 suite / 10 case, failure/error/skip 0으로 통과했다(1분 9초).
초기 harness compile/fixture/기대값 보정 실행은 최종 PASS 수치에 합산하지 않는다.

| 명령 | 이번 fresh 결과 |
| --- | --- |
| T: targeted + architecture/security regression | 23 suite / 167 case PASS, failure/error/skip 0; 7분 9초 |
| T에 포함된 architecture | 6 suite / 32 case PASS, failure/error/skip 0 |
| Recalculation integrity | 기존 Python integrity test 5개 PASS; T/W/K actual raw 각각 재계산 PASS |
| W: 전체 Backend test | 99 suite / 813 case: 804 PASS, 기존 opt-in 9 skip, failure/error 0; 21분 52초 |
| K: clean build + 전체 test 재실행 | 99 suite / 813 case: 804 PASS, 기존 opt-in 9 skip, failure/error 0; 25분 50초 |
| K Boot JAR | Production lexical/knowledge/MCP와 SDK 포함; 신규 integration fixture/model/Python/test runtime 제외 확인 |

T/W/K의 fresh 비교는 #134의 동일 5-document corpus / 18-query oracle / candidate별 2-pass를 실행했다.
Hash-verified MiniLM ONNX/tokenizer revision `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`로
실제 document vector 6개/query vector 30개, successful local batch process 30회를 관측했다.
Random/fixed/hash/mock vector를 embedding 실행으로 세지 않았다. External inference call은 0이며
외부 API 과금은 해당하지 않는다. Host 운영비는 측정하지 않았다.

| T/W/K actual comparison | Lexical | MiniLM |
| --- | --- | --- |
| Category oracle hit | 34/36 | 28/36 |
| Exact source/version hit | 12/14 | 8/14 |
| Tenant/visibility/lifecycle/source safety violation | 0 | 0 |

기존 lexical default/embedding production adoption 보류 근거가 유지된다. 두 후보의 paraphrase
누락과 MiniLM의 partial allergy/operator source 누락을 숨기지 않으며 quality failure를 safety
failure와 구분한다. 작은 synthetic English 결과를 production accuracy/SLA로 일반화하지 않는다.
W/K의 actual comparison 재계산도 각각 동일 hit/safety로 통과했다.
사용자 중단 요청으로 종료한 첫 clean build는 PASS/최종 수치에 포함하지 않는다.
JUnit XML/log/raw comparison/환경 관측은 gitignored root/backend `build/`에만 생성한다.
GitHub CI는 추적하지 않으며 결과를 주장하지 않는다. Frontend 미변경으로 Frontend 검증은 실행하지 않는다.

## Supported scope와 알려진 한계

- 한 live Product JVM의 opt-in MCP/local quota만 지원하며 process/DB 장애와 배포 주기를 공유한다.
  Quota/session은 restart 후 reset될 수 있다. Controlled bearer profile은 범용 OAuth 상호운용 보장이 아니다.
- Product write는 기존 finite DB/HTTP activation gate와 no queue/no redelivery profile을 전제로 한다.
  Client timeout/cancel/disconnect는 rollback이나 실제 work 종료 증거가 아니다.
- Retrieval은 scoped corpus 최대 64개, 결과 최대 5개, excerpt 최대 480 character다. Source별 final
  current metadata observation 이후 변경을 소급 취소하지 않으며 multi-source atomic snapshot도 제공하지 않는다.
- Corpus withdrawal/tombstone은 retrieval ineligibility다. External copy/provider/index hard deletion,
  backup/disk secure erase를 검증하거나 보장한 것은 아니다.
- Audit는 bounded best-effort metadata다. 운영 보존/접근 제한은 sink 설정이 필요하며 durable
  confirmation/business ledger 또는 모든 transport/auth failure의 완전한 기록을 대신하지 않는다.
- Metadata outage/paused retrieval은 현재 production root의 fault 지점에서, provider exception/timeout은
  canonical candidate harness와 actual local model 경로에서 검증한다. External provider 장애를 실행했다고 주장하지 않는다.

## Historical / 미실행 / unsupported

#131~#134 PR 본문과 기존 experiment README는 각 owner 실행 시점의 historical summary이며
이번 fresh PASS와 분리한다. 위 canonical test는 현재 source에서 재실행 가능하다.
Historical summary reference는 [#131 foundation](../mcp-foundation/2026-10-04/README.md),
[#131 negotiation](../mcp-foundation/2026-10-05-negotiation/README.md),
[#132 application guard](../mcp-product-tools/2026-10-05-application-guard/README.md),
[#134 comparison](../knowledge-retrieval/README.md)이며 당시 수치를 옮기지 않는다.

- **미실행**: 기존 opt-in capacity diagnostic 3개와 외부 Kafka secure/fault/evidence fixture 6개,
  M5 operations drill, upstream MCP 전체 conformance, production load/accuracy/SLA.
- **Unsupported / unverified**: OAuth login/discovery, multiple live MCP instance/shared quota,
  external inference provider와 physical provider deletion. 현재 선택된 supported scope에 포함하지 않는다.
  M7 Model Router/Agent Runtime과 M8 generic evaluation/release/backup ownership도 미착수다.
- Model properties가 없을 때의 comparison skip은 embedding 성공으로 계상하지 않는다.
  이번 T/W/K의 actual comparison은 각각 15 retrieval case와 함께 skip 없이 실행됐다.

미실행/unsupported를 PASS로 읽지 않는다.

## 종료 판정

현재 source에서 roadmap의 M6 완료 기준을 다음과 직접 대조했다.

| Roadmap M6 완료 기준 | 현재 구현 / 이번 검증 | 판정 |
| --- | --- | --- |
| Registry/auth context/authorization/timeout/rate/audit | #131 foundation, I/Q와 H/F, redacted correlation | FRESH PASS |
| Transactional 조회/변경은 Product API만 사용 | #132 authenticated HTTPS, I/P durable state와 A dependency guard | FRESH PASS |
| Tenant 안에서 정책/안내를 출처와 검색 | #133/#134 publication authority, I/C/R exact source/version와 actual comparison | FRESH PASS |
| Actor에게 좁혀 위임된 principal | #131 original Actor/current authority, I/H/F; 독립 Agent Runtime은 M7 | FRESH PASS |
| Tenant escape/금지 tool/parameter 조작 test | I/Q/P/R adversarial path, T/W/K failure/error 0 | FRESH PASS |

#131~#134의 primary contract가 유지되고 신규 fresh integration, architecture/security/Product
regression, 전체 Backend test와 clean build, 실제 retrieval 재계산이 통과했다. 중요한 security/
correctness failure와 unresolved M6 blocker는 관측되지 않았다. **지원되는 bounded profile의
M6 AI Access & Knowledge를 Complete로 확정한다.** Shared quota 등 unsupported 항목은 PASS가
아니며 위 limitation을 유지한다. Lexical default/embedding production adoption 보류와 M7/M8
ownership도 유지한다. Commit/push/PR은 이 결과와 문서를 전달하며 merge/CI 성공을 뜻하지 않는다.
