# #132 Product admission gate 최초 진단 — 역사적 FAIL

아래는 guard 구현 **이전**의 실행 기록이다. 60초 gate failure는 유효하지만, 당시의
"guard를 위해 ADR 허용 범위를 별도 확정해야 한다"는 해석은 정정한다. 현재 계약은
gate owner가 기존 Auth PRODUCT expiry/current authority를 application admission에서
재검증하는 좁은 guard로 해결하거나 unsafe topology를 기각하도록 이미 허용한다.
ADR decision을 변경하지 않고 진행한 이후 검증은
[application guard 기록](../2026-10-05-application-guard/README.md)을 참조한다.
아래 미구현/미실행 표기는 당시 상태이며 이후 성공으로 재표기하지 않는다.

2026-10-05 KST 실행. 기준은 직접 fetch한 remote `main`
`62241b921fb2e48caed89d1ecc33b378dbceaa30`이다. #130/#131/#132와 merge된 PR #137,
ADR-0009, M6 architecture, MCP runbook, ADR-0005 및 관련 구현/테스트를 대조했다.
Production source는 변경하지 않았다. 이 기록은 #132 구현 완료, write 활성화 또는 최종 검증 evidence가 아니다.

## 실제 관찰

[진단 source](../../../../backend/src/test/java/com/slotq/mcp/ProductAdmissionGateDiagnosticTests.java)는
실제 TLS Tomcat / Spring Security / Product controller/application / MySQL 8.4를 사용한다.
Test-only `HandlerInterceptor`로 **Product 인증 이후, controller/application/transaction 이전**의
두 request를 정지한다. Clock을 바꾸거나 business result를 mock하지 않는다.
이 hook은 paused execution fault injection이며 production에 추가한 queue나 interceptor가 아니다.
실제 deployment에서 항상 이런 지연이 발생한다는 주장은 하지 않는다. 정지·재개를 포함한
무조건적인 최대 60초 execution bound를 현재 설정만으로 강제하지 못하는 경계를 보여준다.

| 관찰 | 새 실행 결과 |
| --- | --- |
| Product GET authentication | Auth-owned original/delegation으로 발급한 실제 narrowed PRODUCT audience credential. Own-only persisted owner 사용 |
| GET credential expiry | `2026-10-04T17:48:59.615129600Z` |
| 두 request 전송 | `2026-10-04T17:48:00.072386500Z` |
| 인증 이후 정지 해제 | `2026-10-04T17:49:01.072979300Z` — 전송 약 61초 후 |
| Product GET | 만료된 credential로 이미 인증된 request가 재개되어 HTTP `200` |
| 별도 Product HOLD client | 15초에 `HttpTimeoutException` |
| HOLD durable 결과 | 정지 해제 뒤 Reservation 1 / Allocation 1 / COMPLETED idempotency record 1 |
| HOLD `completedAt` | `2026-10-04T17:49:01.176Z` |
| 최종 관찰 elapsed | 61,168 ms |
| Production MCP registry | 여전히 0개. Write operation/credential 등록 없음 |

HOLD는 기존 `local/test` dev Product credential로 기존 HTTP endpoint의 transaction 동작만
검증했다. 이를 MCP → narrowed Product **write** 통합 성공으로 표시하지 않는다.
GET은 실제 #131 downstream credential boundary를 통과한다. 진단용 GET observer timeout은
80초로 설정하여 server 재개 결과를 수집했다. 이것을 지원 Product client의 15초 response
budget으로 표시하지 않는다. HOLD에는 실제 15초 client timeout을 적용했다.

Client timeout 직후에는 대상 Slot의 Reservation/Allocation이 0이었다. 정지 해제 뒤 MySQL
join으로 Reservation/Allocation/COMPLETED row와 `completedAt`을 읽었다. Timeout만으로
rollback/failure를 판단하지 않았으며, key를 바꾸거나 retry하지 않았다. Raw evidence에는
credential과 raw Product key가 없다. UUID와 customer ID는 격리된 synthetic test fixture다.

## 원인과 확정 계약 경계

- `ActorAccessService.issueProduct`는 expiry를 최대 60초로 발급하고 `authenticateProduct`의
  `lookup`은 HTTP authentication에서 expiry/current delegation/route를 검사한다.
- 이후 생성한 `AuthenticatedPrincipal` / `ConsumerRestriction`에는 admission expiry가 없다.
  `AuthorizationUseCase.authorizeReservationRead`는 persisted Tenant/Venue/Customer ownership을
  검사하지만 인증 뒤 경과 시간을 검사하지 않는다.
- `ReservationService.createHold`의 transaction은 application 진입 뒤 시작된다.
  Hikari acquisition/socket 제한과 transaction timeout을 추가하는 것만으로는 **그 이전에
  정지한 request**의 재개를 거부하지 못한다. MCP caller/handler timeout도 Product execution
  종료 증거가 아니다.
- MCP의 no-queue executor와 runner permit accounting은 존재하지만, 그 사실만으로 downstream
  Product application admission의 wall-clock bound를 증명하지 않는다.

[ADR-0009](../../../adr/0009-m6-authenticated-access-and-knowledge.md)의 선택은 다음과 같다.

> Gate 실패 시 write topology를 활성화하지 않는다. 별도 Product command deadline protocol이나
> idempotency 일반화는 도입하지 않는다.

Architecture §6도 지원 환경에서 bound를 보장할 수 없으면 write topology를 기각하고,
무제한 suspended request 재개가 필요하면 ADR 재검토를 먼저 요구한다. #132는 필요성이
입증된 좁은 guard/topology rejection 가능성을 요구하지만, 현재 선택된 ADR의 deadline
금지를 임의로 보정하거나 새 Product protocol을 구현하지 않았다.

검토 가능한 좁은 해결 방향은 Auth-issued server-trusted expiry를 Product admission에서
다시 검사하여 late admission을 거부하는 것이다. Caller deadline claim, Product reliability
lookup, domain/locking 재설계나 generic retry runtime은 필요하지 않다. 다만 guard가 실제
application admission 및 transaction 종료까지 어디에서 어떻게 강제되는지와 기존 ADR의
허용 범위를 확정해야 한다. 이 메모를 안전성 입증이나 구현 완료로 취급하지 않는다.
현재 write gate는 닫혀 있고 이 선택에 대한 사용자 확인을 요청했다.

## 실제 수행한 검증

환경: Temurin JDK 25.0.4 / Gradle Wrapper 9.7.1 / Spring Boot 4.1.1 /
Docker Desktop Linux engine 29.7.2 / disposable MySQL 8.4 / 실제 HTTPS.

Backend directory에서 실행했다.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
.\gradlew.bat test --tests com.slotq.mcp.ProductAdmissionGateDiagnosticTests --offline --no-daemon --console=plain
.\gradlew.bat test --tests com.slotq.mcp.McpAccountingTests --tests com.slotq.mcp.McpFoundationTests --tests com.slotq.ReservationHoldIntegrationTests --tests 'com.slotq.architecture.*' --offline --no-daemon --console=plain
```

- 진단: Gradle `BUILD SUCCESSFUL`, 1분 46초. 테스트는 현재 unsafe 경계를 **재현하는 assertion**이
  성공한 것이다. Write admission gate는 **FAIL**이다. [Raw state](diagnostic.json),
  [실행 log](diagnostic-gradle.log), [source hash](source-sha256.json).
- 관련 기존 회귀: 8 suite / 52 case, failure/error/skip 0, 36초 PASS.
  [실행 log](related-regression-gradle.log), [counts](related-regression-counts.json),
  [raw JUnit XML](related-junit/).
  No-queue saturation/continuing handler accounting/context/schema/redaction, Product same-key
  concurrency/fingerprint conflict/optional-key/rollback/capacity/Policy/24h boundary 및 기존
  architecture suite를 실행했다. 이 결과를 미구현 confirmation/tool/write gate 검증으로
  확장하지 않는다.

전체 Backend test / clean build는 미실행이다. 세 production tool, durable intent/confirmation,
MCP write integration, COMMIT **후** response-loss injection, 모든 DB outage/lock/connection
failure matrix와 write continuing-work accounting은 완료되지 않았다. 이번 HOLD 진단은
**COMMIT 전 client timeout → 이후 COMMIT**이며, 요청된 COMMIT 후 response loss를 대체하지 않는다.
Frontend/remote CI는 실행·추적하지 않았다. Commit/push/PR/merge는 하지 않았다.

진단 fixture만 추가했으며 기존 Product idempotency namespace/fingerprint/transaction/
`completedAt + 24h`, Auth ownership 및 production registry는 변경하지 않았다.
