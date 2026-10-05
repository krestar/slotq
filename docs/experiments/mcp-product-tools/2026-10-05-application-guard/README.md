# #132 Product HTTP fault 재현

한 live Product JVM의 co-located MCP, 고정 HTTPS loopback, MySQL 8.4를 검증한다.
Product HTTP authorization/DTO/transaction/idempotency를 그대로 통과하며, 운영 설정과
controlled original-Actor approval adapter는 [MCP runbook](../../../runbooks/mcp-access.md)을 따른다.

## Canonical test와 oracle

| Test/harness | Fault 주입과 검증 |
| --- | --- |
| [ProductAdmissionGateDiagnosticTests](../../../../backend/src/test/java/com/slotq/mcp/ProductAdmissionGateDiagnosticTests.java) | Authentication 후 controller 이전을 실제 61초 보류. Expired PRODUCT GET/HOLD 401, HOLD의 Reservation/Allocation/idempotency 0. Ordinary dev HOLD는 client timeout 뒤 COMMIT하여 기존 의미 보존 |
| [ProductDatabaseBoundsIntegrationTests](../../../../backend/src/test/java/com/slotq/mcp/ProductDatabaseBoundsIntegrationTests.java) | Pool exhaustion, 실제 Slot lock, blocking SELECT/socket, idle transaction, Docker DB pause. MySQL data_lock_waits/PROCESSLIST/innodb_trx와 durable 세 row를 oracle로 사용 |
| [ProductToolsIntegrationTests](../../../../backend/src/test/java/com/slotq/mcp/ProductToolsIntegrationTests.java) | 실제 production registry/MCP HTTPS → authenticated Product HTTPS → MySQL. Scoped read, exact approval substitution/expiry/replay, capacity/Policy, COMMIT 뒤 TCP loss, same-key retry/known exact GET, saturation 뒤 실제 work accounting |
| [ProductWriteActivationTests](../../../../backend/src/test/java/com/slotq/mcp/ProductWriteActivationTests.java) | Unsafe acquisition/connect/socket/transaction/reconnect/multi-host 및 HTTP redirect profile 거부 |
| [ReservationHoldIntegrationTests](../../../../backend/src/test/java/com/slotq/ReservationHoldIntegrationTests.java) / [McpArchitectureTests](../../../../backend/src/test/java/com/slotq/architecture/McpArchitectureTests.java) | 기존 Product optional key/namespace/fingerprint/24h 전·경계·후 및 dependency 경계 회귀 |

Response-loss hook은 Product controller/transaction 반환 뒤 headers/partial body를 보내고 test-only
Tomcat `CLOSE_NOW`로 실제 TCP 연결을 종료한다. Servlet stream만 닫으면 keep-alive 연결이 남아
timeout 경합을 만들 수 있었다. Target이 없으면 unknown을 유지하고 unrelated GET 404를 mutation
failure로 해석하지 않는다. Location을 받았으면 known target을 기억하고 기존 exact GET으로 확인한다.

Blocking SELECT는 `SELECT 1 FROM venues WHERE SLEEP(20)=0`이다. 단독 `SELECT SLEEP`은
interrupt 시 성공 값 1을 반환할 수 있어 server query 종료 oracle를 대신하지 못한다.
Idle transaction은 같은 tx 안의 세 row 1과 외부 observer의 0을 먼저 확인하고, Java HTTP future가
살아 있는 동안 innodb_trx 종료와 이후 COMMIT 실패를 구분한다. Client timeout/interrupt는 server
실행 종료나 rollback의 증거로 세지 않는다. 실제 filter finally까지 quota permit을 유지한다.

## 재현

JDK 25, Gradle Wrapper, Docker Linux engine과 disposable MySQL이 필요하다. 실제 TLS Tomcat과
HTTPS client를 사용한다. TLS trust-all, pause/fault hook 및 PROCESS observer의 root 권한은
test-only이며 production 설정이 아니다. UUID와 test data는 synthetic fixture다.

`backend/`에서 실행한다.

```powershell
.\gradlew.bat test --tests 'com.slotq.mcp.*' --tests com.slotq.ReservationHoldIntegrationTests --tests 'com.slotq.architecture.*' --tests com.slotq.HoldIdempotencyScopeMigrationTests --offline --no-daemon --console=plain
.\gradlew.bat test --offline --no-daemon --console=plain
.\gradlew.bat clean build --offline --no-daemon --console=plain
```

JSON output은 `build/mcp-product-admission/`, `build/mcp-product-tools/`, JUnit은
`build/test-results/`에 생성한다. 전체 log가 필요하면 `build/` 등 gitignored 위치로 redirect한다.
Run별 XML/JSON/log/hash는 Git에 추가하지 않는다.

#132 구현 당시 최종 관련 regression 95 case와 전체 Backend/clean build의 760 case
(기존 opt-in 9 skip, 실패 0)가 통과했다. 실행 revision과 구체적인 결과는 PR #138에 요약하며,
이 historical PASS를 cleanup 이후의 fresh test 실행으로 표기하지 않는다.

## 지원 한계

Finite DB/HTTP profile과 한 live Product JVM/no queue/no restart redelivery를 지원한다.
Global JVM/DB suspension 중 물리적 종료 시각을 보장하지 않으며, 재개 시 expiry를 검사하고 살아
있는 work를 계속 계상한다. Approval/intent는 durable correctness state이고 best-effort audit로
대체하지 않는다. 공개 approval UI, generic retry orchestration과 tombstone cleanup은 이번 범위에 없다.
