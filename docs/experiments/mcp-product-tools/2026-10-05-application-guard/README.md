# #132 Product tool / application guard 검증

기준 main은 작업 시작 시 fetch하여 확인한 `62241b921fb2e48caed89d1ecc33b378dbceaa30`이다.
Issue #130/#131/#132, merged PR #137, ADR-0005/0009와 현재 source/test를 직접 대조했다.
현재 계약은 gate owner의 좁은 guard 또는 unsafe topology rejection을 허용한다.
ADR decision 변경은 필요하지 않았다. [최초 FAIL](../2026-10-05-admission-gate/README.md)은
guard 이전의 역사적 관찰로 유지한다.

## 구현과 authority

- `ProductToolConfiguration`의 명시적 production root가 세 tool만 등록한다. Auth/Product는
  MCP integration을 역참조하지 않으며 MCP integration에는 Product repository/JPA dependency가 없다.
- `reservation.get`는 기존 persisted own-only authorization을 재사용한다. #131이 이미 제공한
  own-only 제약을 새로 복제하지 않았다. `management.reservations.list`는 Owner/Manager/Staff의
  기존 scoped Product DTO/effective state/status/allowedActions를 유지한다.
- `reservation.hold`는 original Actor 제어 API의 durable review/approval 후 기존 HOLD HTTP API를
  호출한다. 원 Actor/delegation/Tenant/Venue/tool/action/Slot/partySize/generated key를 immutable
  intent에 묶고 approval FK로 참조한다. Slot→Resource와 capacity/Policy는 Product가 최종 검증한다.
- Approval/intent는 MCP 소유의 MySQL 두 table에 저장한다. Product reliability table은 조회하지
  않는다. 최초 dispatch row lock/CAS, approval 최대 5분, retry 최초 dispatch+15분을 보존한다.
  DATETIME(6) material의 시각은 microsecond precision으로 저장한다. Renewal은 first dispatch/key를
  바꾸지 않으며 새 intent는 항상 새 key다. Cleanup 없이 보존하여 최소 25시간 tombstone을 충족한다.
- Product `(tenant, authenticated customer, key)` namespace, `(venue, slot, partySize)` fingerprint,
  동일 transaction의 Reservation/Allocation/idempotency와 successful completedAt+24h를 변경하지 않는다.
  Ordinary Product의 optional key도 유지한다.

## 60초 gate와 실제 종료

Product authentication만으로 application admission까지의 expiry가 보존되지 않았던 최초 fault를
같은 post-security/pre-controller `HandlerInterceptor` 위치에서 **실제 61초** 정지·재개한다.
Narrowed GET과 material-bound PRODUCT HOLD가 모두 401이며 guarded HOLD의 durable Reservation,
Allocation, idempotency row가 모두 0이어야 한다. 기존 dev Product HOLD는 15초 client timeout
이후에도 재개·COMMIT하여 ordinary Product semantics가 유지되는지 함께 검사한다. 이 진단은
명시적 빈 test root를 사용한다. 세 production tool의 실제 MCP 경로는 별도 integration suite가 검증한다.

Guard는 기존 PRODUCT credential UUID를 current Auth transaction으로 다시 읽고 original Actor,
delegation, profile, tool/action, Tenant/Venue, operation, exact target, HOLD partySize/key digest 및
expiry를 검사한다. HOLD Slot lock 이후에도 재검증한다. Caller deadline claim이나 Product key
protocol은 없다. MCP handler deadline은 admittedAt+최대 30초이며 downstream expiry가 이보다
늦지 않다. 로컬 monotonic expiry는 credential issuance 후의 wall-clock 후퇴에도 최대 60초보다
늦은 재개를 허용하지 않는다. Origin은 같은 JVM의 고정 HTTPS loopback이다.

Finite supported profile은 acquisition/connect/socket≤2초, row/metadata lock≤2초,
server read SELECT≤2,000ms, idle session≤10초, default transaction≤10초다. 검증 fixture는
idle session을 5초로 설정한다. Single-host MySQL과 no reconnect, no JDK HTTP retry/redirect를
요구하며 unsafe profile은 활성화 전에 거부한다.

| Fault | 별도 server/durable oracle |
| --- | --- |
| Pool exhaustion | bounded Auth acquisition의 503, Product row 0, pool 반환 후 정상 HOLD |
| Slot lock | lock owner를 해제하기 전 MySQL data_lock_waits 0, Product 세 row 0, release 후 정상 HOLD |
| Query/socket | 실제 SELECT SLEEP 주입, MySQL PROCESSLIST의 해당 query 종료와 Product 세 row 0 |
| Idle Product transaction | 동일 tx의 세 row 1을 먼저 관찰, 외부 observer는 0; innodb_trx 종료 후에도 Java HTTP future는 살아 있음; 재개 후 COMMIT 실패/세 row 0 |
| DB unavailable | 실제 Docker MySQL pause, Auth unavailable; unpause 후 durable state와 명시적 retry 검증 |
| Response/network loss | controller/transaction 반환 **뒤** 201 본문을 중간에서 종료; MySQL 세 row 1; 자동 retry 없음 |
| Saturation/accounting | 실제 Product 응답을 보류한 네 work가 MCP timeout 후에도 네 permit 점유; 다섯 번째 거부; 실제 filter finally 후 반환 |
| Auth current read 보류 | HTTP timeout 후 Auth 작업도 permit 유지; 실제 credential expiry 이후 재개하면 application effect 0 |

MySQL [max_execution_time](https://dev.mysql.com/doc/refman/8.4/en/server-system-variables.html)과
[SLEEP interruption](https://dev.mysql.com/doc/refman/8.4/en/miscellaneous-functions.html)의 의미를
구분한다. 단독 `SELECT SLEEP`은 interrupt 시 성공 값 1을 반환할 수 있으므로 injected query는
`SELECT 1 FROM venues WHERE SLEEP(20)=0`이다. 최초 시도에서 client query/socket timeout만으로
server query가 종료되지 않는 것을 관찰했고, server SELECT cap을 포함한 profile로 재검증했다.
[최초 실패 raw](db-bounds-first-attempt.xml)/[log](db-bounds-first-attempt.log)와
[그 후 5-case PASS](db-bounds-pass.xml)/[log](db-bounds-pass.log)를 별도로 보존한다.

Client timeout/interrupt를 remote execution 종료로 계상하지 않는다. Auth의 local accounting은
인증 authority가 아니며, 실제 Product filter finally까지 current Auth read/servlet 작업을 추적한다.
Adapter가 종료를 기다리는 동안 MCP runner가 permit을 반환하지 않는다. Global JVM/DB suspension
중 물리적 종료 시각을 주장하지 않으며 재개 시 expiry를 검사하고 살아 있는 작업을 계속 계상한다.

## Response loss와 replay

실제 COMMIT 뒤 response-body EOF를 주입한다. Location까지 잃으면 outcome unknown / known target
없음을 유지한다. 임의 ID의 exact GET 404도 최초 mutation failure로 해석하지 않는다. 명시적
same-intent retry 한 번은 같은 immutable key로 최초 Reservation을 받으며 completedAt을 바꾸지 않는다.
Location이 이미 도착한 경우 known ID를 intent와 result metadata에 보존하고 existing exact GET으로
reconcile한다. 무작위 reliability lookup, 자동 key 교체, 자동 mutation retry는 없다.

## 실행 명령과 provenance

Temurin JDK 25.0.4.101 / Gradle Wrapper 9.7.1 / Boot 4.1.1 / Docker Desktop Linux engine 29.7.2 /
disposable MySQL 8.4 / 실제 TLS Tomcat과 HTTPS client를 사용한다. TLS trust-all과 fault injection,
PROCESS/innodb_trx observer의 root connection은 test-only다. Production DB 권한을 확대하지 않는다.
UUID는 synthetic fixture이며 credential/raw Product key/PII는 evidence에 export하지 않는다.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
Set-Location backend
.\gradlew.bat test --tests 'com.slotq.mcp.*' --tests com.slotq.ReservationHoldIntegrationTests --tests 'com.slotq.architecture.*' --tests com.slotq.HoldIdempotencyScopeMigrationTests --offline --no-daemon --console=plain
.\gradlew.bat test --offline --no-daemon --console=plain
.\gradlew.bat clean build --offline --no-daemon --console=plain
```

최종 실행 결과와 source/artifact hash는 [verification.json](verification.json)으로 보존한다.
최종 검증 결과는 다음과 같다. 이전 `guard-targeted` 8-suite/58-case와 `db-bounds-pass` 5-case는
중간 guard 단계의 실행이며 최종 production registry 검증으로 재표기하지 않는다.

| 최종 실행 | 실제 결과 | raw evidence |
| --- | --- | --- |
| 관련 regression | 15 suite / 95 case, failure/error/skip 0, 3분 42초 PASS | [log](final-targeted.log), [counts](final-targeted-counts.json), [JUnit](final-targeted-junit/) |
| 전체 Backend test | 96 suite / 760 case, 751 실행 성공 / 기존 opt-in 9 skip, failure/error 0, 18분 42초 PASS | [log](backend-test.log), [counts](backend-test-counts.json), [JUnit](backend-test-junit/) |
| clean build | 새 Boot JAR + 전체 test 재실행, 동일 96 suite / 760 case 결과, 19분 7초 PASS | [log](clean-build.log), [counts](clean-build-counts.json), [JUnit](clean-build-junit/) |
| Architecture | 5 suite / 29 case, failure/error/skip 0 | 위 final JUnit의 architecture suites |

기존 opt-in skip은 capacity-lock diagnostic 3개와 외부 Kafka security/fault/evidence fixture
6개다. #132 targeted regression에는 skip이 없다. 해당 외부 fixture의 새 성공을 주장하지 않는다.
Final source 26개 hash는 전체 test/clean build 이후에도 일치한다. Boot JAR에는 실제 production
adapter와 auto-configuration registration 및 SDK 2.0.1이 포함되며 test-only fault class는 없다.
JAR SHA-256도 verification에 기록했다. 이후 변경은 이 결과를 기록하는 문서뿐이다.

`targeted.log`/`targeted-counts.json`/`targeted-junit`는 15-suite/95-case PASS(3분 48초) 기록이며,
당시 source는 `source-before-tcp-close-sha256.json`이다. 그 뒤 최초 전체 Backend 실행은
759 case / 1 failure / 8 skip(18분 52초)이었다. Failure는 불완전한 응답을 Servlet stream에서
닫아도 keep-alive TCP 연결이 종료되지 않아 Product response deadline과 MCP deadline이
경합한 test fixture에서 발생했다. [전체 실패 log](backend-test-first-attempt.log)와
[해당 suite XML](backend-test-first-attempt-product-tools.xml)을 별도로 보존한다.

Test-only Tomcat `CLOSE_NOW`로 COMMIT 이후 flushed headers/partial body의 실제 연결을
종료하도록 보강했다. Production source는 수정하지 않았다. 영향 범위의 13-case regression은
59초 PASS이며 [log](response-fault-regression.log)/[XML](response-fault-regression.xml)/
[counts](response-fault-counts.json)을 보존한다. [현재 unknown-target sample](focused-response-loss-unknown.json)과
[known-target sample](focused-response-loss-known.json)은 이 보강된 fault의 실제 결과다.
기존 `response-loss-*.json`은 보강 전 targeted 실행이며 같은 실행으로 재표기하지 않는다.
이후 pinned SDK validator를 직접 호출한 [probe](schema-format-probe-before-pattern.log)에서
UUID `format`은 annotation으로 취급되어 길이 36의 invalid UUID가 schema를 통과하는 것을
확인했다. #132 UUID schema에 canonical pattern을 명시하고 invalid LocalDate는 handler의
미전송 validation result로 반환하도록 보강했다. Product/공통 runtime 계약은 바꾸지 않았다.
Malformed UUID/date regression을 추가했고, 수정 전 진행 중이던 전체 test는 중단하여
[별도 log](backend-test-interrupted-before-final-schema.log)로 보존한다. 이 실행은 PASS가 아니다.
최종 관련 regression과 이후 전체 test/clean build는 `source-sha256.json`의 final source로 수행한다.

실제 common validation/timeout payload를 output validator에 전달한
[probe](schema-output-probe-before-union.log)에서도 공통 `requestId`/`unknown` 누락을 확인했다.
#132 output schema에 이 공통 error union을 포함하고 integration test의 모든 실제 structured
wire result를 해당 등록 schema로 검사한다. 실제 transport timeout의 non-RPC response도
가짜 RPC result로 바꾸지 않고 그대로 관찰한다. 최종 related regression은
[log](final-targeted.log)/[counts](final-targeted-counts.json)/[JUnit](final-targeted-junit/)의
15-suite/95-case, failure/error/skip 0, 3분 42초 PASS다. [61초 gate](final-guard.json),
[unknown target](final-response-loss-unknown.json), [known target](final-response-loss-known.json)은
이 최종 source의 실행이다.

## 지원 한계

한 live Product JVM/local quota, controlled original Actor adapter, no queue/no restart redelivery,
명시적 same-intent retry만 지원한다. 공개 approval UI/onboarding, tombstone cleanup UI, generic workflow,
M7 retry runtime, #133/#134 RAG, M8 evaluation은 구현하지 않았다. Audit는 best-effort metadata이며
durable approval을 대신하지 않는다. Frontend/remote CI/merge는 실행·추적하지 않는다.
