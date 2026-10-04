# #131 MCP access foundation evidence

Baseline은 fetch한 remote main `40e764bf0c7d23a6a3fd98c274ca634f60a1ac77`이다. #130 완료/#136
merge 뒤의 ADR-0009/M6 contract를 적용했다. Runtime/runbook과 후속 ownership은
[MCP access](../../../runbooks/mcp-access.md)에 기록한다. 여기의 wire/audit data는 synthetic
test fixture이며 credential, 원문 query/document 또는 실제 Customer 데이터가 아니다.

## 선택한 implementation

Java SDK **2.0.1**의 `mcp-core` wire result/tool model과 `mcp-json-jackson3` schema validator를
사용하는 작은 servlet adapter다. SDK의 server scheduler/session executor나 Spring AI starter를
활성화하지 않는다. Lifecycle/session/HTTP ingress와 no-queue runner를 직접 제한한다.
Java 25.0.4 / Spring Boot 4.1.1 / Gradle Wrapper 9.7.1 / Jackson 3.1.5에서 실제 resolution,
MySQL migration/application startup, TLS servlet endpoint와 SDK Java client invocation을 실행했다.

| 실제 resolved artifact | SHA-256 |
| --- | --- |
| `io.modelcontextprotocol.sdk:mcp-core:2.0.1` | `1217c23097ca3bd36121ef14daf14a55ff07aa3777e31b578894407bd98a3735` |
| `io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.1` | `af8c33709d139918cd75b9ea829b9fa95d0f9fb8eea6afb326de14bbdfadc0eb` |
| `tools.jackson.core:jackson-databind:3.1.5` | `3a2338d996fd3056791df8d335fa9ba8a62a706ed4245ecf81b3e583df37d08a` |
| `com.networknt:json-schema-validator:3.0.6` | `f6f727370bbc53de6d1321710b7e90288774042e69e3bb6992d6801048f56800` |

`McpHttpIntegrationTests`는 disposable MySQL 8.4와 ephemeral TLS certificate로 실제 Boot/Tomcat을
시작한다. Test-only tools는 `McpRegistrations`로만 등록하며 production registry는 empty다.
Protocol target은 `2025-11-25`/Streamable HTTP/tools only/initialize lifecycle이다. Java SDK client의
initialize, list, structured success, `isError`, forbidden direct call, unknown tool과 deterministic
JSON-RPC transcript를 검증한다. SDK upstream conformance 전체 PASS를 주장하지 않는다.

## 직접 검증한 경계

| 요구 | Regression/evidence |
| --- | --- |
| Real lifecycle/version/capability/result/error | Actual HTTPS SDK client + initialize → initialized → list/call. Unsupported revision/capability, premature list, wrong header/session, missing session 400, terminated/bound session 404와 reinitialize, GET/DELETE version header, malformed/duplicate JSON, unknown tool, structured success/`isError`, error RPC ID matching |
| Profiles/enumeration/direct authorization | Customer/management enumeration과 direct forbidden call. 한 tool의 immutable profile별 permission 등록, current action/tool grant 각각 없는 호출 거부. Same subject의 다른 delegation으로 session 사용 거부. Extra/type/required/min/max/oversize와 principal/tenant/role/allowlist injection 거부 |
| Trusted original/current delegation | MySQL digest/audience record. Exact expiry, delegation/original credential revoke, underlying membership 제거, capability 확대 issuance 거부. RR outer snapshot 뒤 revoke를 별도 current read에서 확인 |
| M5 substitution/authoring boundary | 실제 operations store가 인정한 generated M5 recovery token과 configured scrape token을 MCP/Product/original validator에서 거부. Original configuration validator는 delegate를 거부 |
| Product consumer restriction | Distinct 최대 60초 Product credential, exact method/route/target, fixed-origin prepared request. Real persisted Reservation HTTP에서 operator membership을 가진 Customer도 다른 고객은 404, own은 200 |
| Context/deadline/concurrency | 서로 다른 Customer/management principal/Tenant/context를 동시 실행하고 또 다른 executor로 전달. Immutable input mutation 거부. Monotonic wait cap/clock regression guard. Real HTTP timeout + continuing handler와 unit latch에서 실제 종료 전 permit 유지/새 attempt 즉시 거부 |
| Runtime/registry/dependency | 실제 Tomcat maxThreads/maxConnections/acceptCount/upload timeout 값 확인. Inactive Product/isolated role/subpath 404, unsafe activation 거부, arbitrary component bean이 tool이 되지 않음. Architecture bytecode dependency 검사 |
| Local quota/accounting | Principal aggregate renewal bypass 거부, rate/burst/concurrency saturation, validation/forbidden/retry no refund, fixed unknown bucket, bounded cardinality, no in-flight/indebted eviction, unavailable/clock failure, restart reset limitation |
| Audit/redaction/failure | Credential/PII/prompt/client object/provider error sentinel의 success/deny/unknown/timeout 검증. Queue full/drop + sink exception이 success를 뒤집지 않음. Handler/downstream timeout layer, known target/Product ID 보존. File sink 7일/finite bytes 및 unrelated file 보존 |

Raw samples:

- [HTTP JSON-RPC transcript](interoperability.json)
- [HTTP session lifecycle statuses](session-lifecycle.json)
- [Allow/deny audit](audit-allow-deny.json)
- [Handler timeout audit](audit-timeout.json)
- [Uncontrolled exception → unknown audit](audit-unknown.json)
- [Declared downstream unknown + known reference audit](audit-downstream-unknown.json)

각 JSON은 해당 regression의 실제 응답/event를 export한다. Test source의 `samples`/transcript export로
재생성할 수 있다. RPC/UUID/latency 값은 run마다 달라질 수 있으며 raw identity를 다른 run의
current evidence로 relabel하지 않는다. Known-reference downstream sample은 test-only handler의
declared result이며 실제 Product mutation/response-loss evidence로 표시하지 않는다.

## 검증 명령과 제한

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
Set-Location backend
.\gradlew.bat dependencies --configuration runtimeClasspath --console=plain
.\gradlew.bat test --tests 'com.slotq.mcp.*' --tests 'com.slotq.architecture.*' --tests 'com.slotq.observability.web.RequestCorrelationFilterTests' --tests com.slotq.AuthWebIntegrationTests --tests com.slotq.HoldIdempotencyScopeMigrationTests --tests com.slotq.SlotqApplicationTests --offline --no-daemon --console=plain
.\gradlew.bat test --offline --no-daemon --console=plain
.\gradlew.bat clean build --offline --no-daemon --console=plain
```

최종 결과/source hash와 생성한 Boot JAR의 hash는 [verification.json](verification.json)에 기록한다.

| 최종 실행 | 결과 |
| --- | --- |
| Targeted security/protocol/concurrency + 기존 Auth/migration/startup + architecture | 13 suite / 75 case, failure/error/skip 0, 1분 28초 PASS |
| 전체 Backend test | 92 suite / 734 case, 725 실행 성공, 기존 opt-in 9 skip, failure/error 0, 16분 2초 PASS |
| clean build (새 artifact + 전체 test 재실행) | 92 suite / 734 case, 725 실행 성공, 기존 opt-in 9 skip, failure/error 0, 16분 19초 PASS |
| Architecture (targeted와 두 full 실행에 포함) | 5 suite / 28 case, failure/error/skip 0 PASS |

Skip은 기존 capacity-lock diagnostic 3개와 Kafka 외부 secure/fault/evidence property 기반 6개다.
해당 별도 fixture/과거 evidence의 fresh 실행 성공을 주장하지 않는다. #131 관련 regression에는
skip이 없다. 43개 검증 source hash는 마지막 targeted 뒤 두 final 실행에서 변경 없이 유지됐다.

Final contract 보강 전에 시작했다가 중단한 full test는 PASS evidence로 세지 않는다.
Final targeted regression 이후 production/test source를 변경하지 않은 상태로 전체 Backend test와
clean build를 수행했다. Remote CI는 추적하거나 성공으로 주장하지 않으며 merge하지 않는다.

V21 추가에 따른 기존 Auth schema 검사는 digest/reference 두 column만 정확히 허용하고
plaintext credential 금지는 유지한다. 기존 migration/startup 검사의 latest version 기대값은
21로 갱신하며 해당 세 suite도 final targeted regression에 포함한다.

Local-only quota/one live MCP instance, controlled non-OAuth clients, shared Product JVM/DB,
best-effort audit와 운영 sink 접근 제어가 지원 한계다. No-queue/explicit handler permit을 검증했지만
모든 arbitrary handler/provider의 실제 종료를 보장하지 않는다. 멈추지 않는 handler는 permit을
차지해 capacity를 감소시킨다. Timeout/disconnect/interrupt는 Product rollback 증거가 아니다.

Write Product credential/tool과 실제 세 Product tool은 활성화하지 않았다. #132가 confirmation/
intent/idempotency/reconciliation, 실제 HTTP client의 no retry/redirect와 remote Product/DB execution,
maximum 60초 command admission bound를 추가 검증한다. Corpus authoring은 #133, concrete retrieval/
provider/index는 #134, fresh 전체 M6 integration/closure는 #135다. 이 evidence를 그 gate의 PASS로
대신 사용하지 않는다.
