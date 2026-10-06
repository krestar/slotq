# M6 Access & Knowledge 계약

> 상태: #130에서 확정한 구현 전 계약. Production 구현/evidence는 #131~#135가 소유한다.
>
> 결정: [ADR-0009](../adr/0009-m6-authenticated-access-and-knowledge.md)
>
> 확인일: 2026-10-04, remote main `688c1a58cd9b8a95b41ba1fc7f2d763bb13b73b4`.
>
> 아래의 `현재`/후속 gate 표현은 #130 설계 시점의 snapshot이다. Merged 구현과 현재 source의
> 실행·종료 판정은 [#135 통합 검증](../experiments/m6-closure/README.md)을 따른다.

### 검증 산출물 정책

M6의 `evidence`는 기본적으로 최신 source에서 재실행 가능한 test/harness, 실행 명령,
CI/local 결과 요약과 필요한 architecture/runbook을 뜻한다. JUnit XML, 전체 build log,
raw JSON/CSV, source hash 묶음, fault dump, provider/container output 등 run별 실행 산출물은
Git에 commit하지 않는다. 필요한 raw output은 `build/` 또는 다른 gitignored 경로에서 생성한다.
Issue/ADR이 특정 artifact의 장기 버전 관리를 명시적으로 요구할 때만 예외로 추적한다.

## 1. 현재 사실과 변경 경계

Remote main은 #129 M5 완료 문서 commit이며 프롬프트 baseline과 같다. 현재 #130~#135는
모두 open이고 merged M6 구현/ADR은 없다. Product Scope의 Actor/authority, Domain Model의
AI dependency, ADR-0002의 보존된 module 규칙, ADR-0005/0006/0008과 실제 source를 대조했다.
이 문서에서 **현재**는 확인한 구현, **계약/gate**는 후속 Issue가 구현·검증할 요구를 뜻한다.

| 책임 | 현재 근거 | M6 적용 |
| --- | --- | --- |
| Product authority | [Product Scope §5](../product-scope.md), [Domain Model §3](domain-model.md) | Availability/capacity, Reservation/HOLD/cancel/lifecycle, Waitlist, Actor/Tenant, structured enforced policy는 계속 Product authority |
| Backend ownership | [Repository Layout](repository-layout.md), [ADR-0002](../adr/0002-start-with-modular-monolith.md) | 같은 Backend build 안의 좁은 MCP/knowledge 경계. Product에서 AI로 dependency 추가 금지 |
| M5 운영 | [ADR-0008](../adr/0008-m5-event-transport-and-runtime-status.md), [Operator Recovery](operator-recovery.md) | DB direct supported default/Kafka experimental 유지. Recovery/scrape credential과 grant를 AI로 전용하지 않음 |
| 미래 ownership | [Roadmap M6~M8](../roadmap.md#m6-ai-access--knowledge) | M6는 bounded tools/knowledge, M7은 Router/Agent Runtime, M8은 generic evaluation/hardening |

## 2. Product enforcement matrix

아래 경로는 MCP 구현 여부와 관계없이 현재 Product source에 존재한다. MCP adapter는 HTTP
boundary를 사용하고 Product domain/transaction/locking을 복제하지 않는다.

| Responsibility | 현재 강제 위치와 의미 | M6 gap/owner |
| --- | --- | --- |
| External credential → principal | [BearerCredentialAuthenticationFilter](../../backend/src/main/java/com/slotq/auth/web/BearerCredentialAuthenticationFilter.java) → [BearerCredentialResolver](../../backend/src/main/java/com/slotq/auth/web/BearerCredentialResolver.java) → `AuthenticatedPrincipalAuthentication`. [DevAuthConfiguration](../../backend/src/main/java/com/slotq/auth/dev/DevAuthConfiguration.java)/[DevCredentialStore](../../backend/src/main/java/com/slotq/auth/dev/DevCredentialStore.java)는 local/test + explicit flag의 random token mapping | Production resolver 없음. #131이 trusted Actor credential mapping과 narrowed Product credential 검증을 Auth boundary에 구현. 임의 Principal constructor 호출은 인증 아님 |
| Authentication | [SecurityConfiguration](../../backend/src/main/java/com/slotq/auth/web/SecurityConfiguration.java)의 stateless bearer chain. 공개 Venue/Availability와 dev session 이외 요청은 authenticated. Resolver 부재/invalid bearer는 protected API에 권한을 만들지 못함 | #131은 MCP 전용 matcher/credential namespace를 추가. MCP token을 기존 Product token으로 무검증 사용 금지 |
| Tenant/venue derivation | HOLD: [ReservationService.createHold](../../backend/src/main/java/com/slotq/booking/application/ReservationService.java)의 path venue + persisted Slot에서 tenant. Exact GET은 persisted Reservation. Management: [AuthorizationUseCase.requireVenueAccess](../../backend/src/main/java/com/slotq/auth/application/AuthorizationUseCase.java)/[AccessControlPersistenceAdapter.findActorForVenue](../../backend/src/main/java/com/slotq/auth/persistence/AccessControlPersistenceAdapter.java)의 Venue↔membership/grant join | #131/#132는 grant scope와 저장된 Product ownership 일치 확인. Caller tenant/role는 schema에 없음 |
| Authorization | Exact GET: `authorizeReservationRead`는 owner Customer 또는 scoped operator. HOLD 생성은 authenticated subject로 자신의 예약을 만들며 operator membership을 요구하지 않음. [ManagementService.getReservations](../../backend/src/main/java/com/slotq/management/application/ManagementService.java)는 `requireVenueAccess`, Owner/Manager/Staff를 허용 | #132 `reservation.get`는 이보다 좁은 own-resource만 노출. Operator 권한을 Customer ownership으로 치환하지 않음 |
| HTTP/wire validation | [ReservationController](../../backend/src/main/java/com/slotq/booking/web/ReservationController.java): UUID path/body, `@NotNull`, `@Min(1)`, optional `Idempotency-Key`. [ManagementController](../../backend/src/main/java/com/slotq/management/web/ManagementController.java): LocalDate/status binding. [application.properties](../../backend/src/main/resources/application.properties): unknown field rejection | #131 strict MCP schema; #132 HTTP validation을 통과. MCP HOLD key 필수화는 기존 optional header 계약을 변경하지 않음 |
| Application validation | `ReservationService`: ACTIVE Tenant/Venue/Resource, future Slot, partySize/seatingCapacity, effective capacity. `ManagementService`: Venue timezone의 date window, effective state/status, allowedActions 계산 | #132는 authoritative DTO/error를 보존. Management response의 `allowedActions`가 write tool 권한을 부여하지 않음 |
| Domain validation | [Reservation](../../backend/src/main/java/com/slotq/booking/domain/Reservation.java), [PartySize](../../backend/src/main/java/com/slotq/booking/domain/PartySize.java), [Resource](../../backend/src/main/java/com/slotq/venue/domain/Resource.java), [BookingPolicy](../../backend/src/main/java/com/slotq/venue/domain/BookingPolicy.java): ownership/eligibility/deadline/policy/lifecycle invariant | 자연어 policy나 MCP에서 domain invariant 재결정 금지 |
| Idempotency | [HoldIdempotencyKey](../../backend/src/main/java/com/slotq/booking/application/HoldIdempotencyKey.java), [HoldIdempotencyPolicy](../../backend/src/main/java/com/slotq/booking/application/HoldIdempotencyPolicy.java), [HoldIdempotencyPersistenceAdapter](../../backend/src/main/java/com/slotq/booking/persistence/HoldIdempotencyPersistenceAdapter.java): scoped unique key, semantic fingerprint, locking current read, completion/retention | #132의 intent binding은 별도 MCP 상태이며 Product reliability table lookup/write 권한 없음 |
| Transaction/rollback | `ReservationService.createHold @Transactional`: reliability/Reservation/Allocation 동일 MySQL transaction. 실패는 함께 rollback. GET/management list는 read-only transaction. [ReservationCommandExecutor](../../backend/src/main/java/com/slotq/booking/application/ReservationCommandExecutor.java)의 transition transaction은 별도 공개 command 경로 | HTTP response timeout/disconnect는 commit/rollback 증거 아님 |
| Lock/concurrency | [SlotInventorySpringDataRepository](../../backend/src/main/java/com/slotq/booking/persistence/SlotInventorySpringDataRepository.java)의 scoped PESSIMISTIC_WRITE. HOLD는 Slot 먼저 lock. [ReservationSpringDataRepository](../../backend/src/main/java/com/slotq/booking/persistence/ReservationSpringDataRepository.java)의 effective occupancy/current read, idempotency unique insert + FOR UPDATE. [ADR-0006](../adr/0006-use-targeted-pessimistic-locks-for-reservation-consistency.md) | #132는 same-key/capacity regression 재사용. New lock/Redis deduplication 금지 |
| Result/error | `ReservationController`: HOLD `201`/Location, GET no-store/effective state. Management GET no-store/effective state/allowedActions. [ProductApiExceptionHandler](../../backend/src/main/java/com/slotq/web/ProductApiExceptionHandler.java)/[ProductApiSecurityProblemWriter](../../backend/src/main/java/com/slotq/web/ProductApiSecurityProblemWriter.java): 400 validation, 401 auth, 403 permission, 404 hiding, 409 domain/key conflict, 500 system failure | #132 tool error는 code/category와 unknown 여부 보존. 404/system failure를 write rollback으로 재해석하지 않음 |
| Audit/correlation | [RequestCorrelationFilter](../../backend/src/main/java/com/slotq/observability/web/RequestCorrelationFilter.java)는 incoming request/trace를 신뢰하지 않고 fresh `X-Request-ID` 생성. [ProductTelemetry](../../backend/src/main/java/com/slotq/observability/ProductTelemetry.java)/[logback](../../backend/src/main/resources/logback-spring.xml)은 allowlisted 비차단 telemetry | 현재 Product telemetry는 durable delegated audit가 아님. #131 MCP audit, #132 MCP request↔Product response request ID mapping. Unverified incoming correlation을 authority로 사용하지 않음 |

확인한 regression source:
[ReservationHoldIntegrationTests](../../backend/src/test/java/com/slotq/ReservationHoldIntegrationTests.java)
의 key replay/concurrency/fingerprint/scope/rollback/retention/validation/auth cases,
[ManagementApiIntegrationTests](../../backend/src/test/java/com/slotq/ManagementApiIntegrationTests.java)
의 role/tenant/venue/effective state/no-store cases. 이 검토에서 해당 MySQL suite를 재실행한 것은 아니다.

현재 HOLD path는 synchronous이고 외부 queue/redelivery를 호출하지 않는다. 그러나
`createHold`의 `@Transactional`에는 explicit timeout이 없고 base configuration은 HOLD의
DB socket/query/전체 admission 상한을 정하지 않는다. 현재 구현을 이미 60초 bounded라고
표기하지 않는다. #131/#132의 활성화 gate는 §6에서 정의한다.

## 3. Runtime / protocol / authentication profile

```text
controlled MCP client
  → HTTPS /mcp (opt-in, independent credential/matcher)
  → registry / delegated admission / bounded handler
  → fixed-origin authenticated HTTP Product API → current Product/MySQL
  → knowledge public port → scoped current corpus / derived index
```

한 live MCP instance를 Product JVM에 둔다. 별도 process isolation/scaling을 주장하지 않는다.
MCP ingress, JSON body/result 크기, executor, HTTP connection과 provider concurrency는 bounded다.
MCP handler가 servlet thread를 전부 점유해 자기 Product HTTP 호출을 막지 않도록 별도
bounded executor와 Product thread 여유를 검증한다. Saturation은 enqueue 대신 거부한다.
MCP 미활성 runtime과 M5 isolated role에는 `/mcp`가 노출되지 않는다.
Registry/common admission은 concrete Product/retrieval handler를 역참조하지 않는다. Integration
composition root가 좁은 handler contract에 adapter를 등록한다. Credential/delegation은 Auth,
confirmation/intent는 MCP Product integration, document/version/publication/index는 knowledge가
각자의 entity/table을 소유하며 다른 owner의 JPA/repository를 공유하지 않는다.

| 공식 확인(2026-10-04) | 선택/gate |
| --- | --- |
| [Latest spec](https://modelcontextprotocol.io/specification/latest)는 `2026-07-28`로 redirect. [Changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)의 stateless/per-request 변화 | Target `2025-11-25`. Revision을 negotiate한 실제 client evidence로만 지원 표기 |
| [Java SDK v2.0.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1), [release lines](https://github.com/modelcontextprotocol/java-sdk/blob/main/CHANGELOG.md)는 `2025-11-25` 지원. [tag POM](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/pom.xml)은 Java 17 compilation target | Java 25를 낮추거나 다른 언어/service를 추가하지 않음. Exact resolved artifact/version/hash를 #131 evidence에 고정 |
| [Spring AI 2.0.x](https://docs.spring.io/spring-ai/reference/getting-started.html)는 Boot 4.0/4.1 지원. [WebMVC starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)는 Streamable HTTP 제공, endpoint는 기본 unauthenticated | `spring-ai-starter-mcp-server-webmvc` 2.0.x stable 후보, 또는 같은 SDK의 최소 servlet adapter. #131이 Java25/Boot4.1.1/Jackson3 classpath/start와 wire test로 하나를 pin. Auto scan으로 unintended tool 등록 금지 |
| [MCP Security 문서](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html)는 community/WIP라고 명시 | 설치 자체를 security evidence로 세지 않음. 기존 Spring Security + Auth public boundary로 구현 |
| [Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle), [Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports), [Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools) | Initialize → initialized → list/call, negotiated header/version, JSON/SSE response와 transport error를 deterministic client로 검증. Tools capability만 광고 |

#131의 harness는 raw JSON-RPC transcript와 stable Java client를 실제 HTTP endpoint에 연결한다.
Customer/management profile별 list/call, unsupported revision, unknown tool, malformed/extra
arguments, structured result/output schema, `isError`와 protocol error 구분, session 사용 시
credential/principal binding을 검증한다. SDK upstream conformance PASS는 SlotQ PASS가 아니다.
SDK가 지원하지 않는 최신 revision, Tasks/redelivery, auth discovery를 광고하지 않는다.

Auth는 제한된 non-OAuth profile이다. [HTTP auth spec](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)의
OAuth 권고에서 벗어나는 범위는 controlled pre-provisioned clients뿐이며 범용 MCP auth
interoperability를 주장하지 않는다. HTTPS와 exact allowed Origin/Host를 강제한다. Browser
origin은 기본 거부하고 필요 시 명시적인 allowlist만 사용한다. Loopback도 credential을
검증하며 caller가 Product base URL/redirect destination을 정할 수 없다. Redirect에 credential을
전달하지 않는다. OAuth issuer/discovery/redirect flow는 이 profile에 존재하지 않는다.

### Trusted identity와 delegation

Original Actor는 서버가 사전 provision한 individual opaque credential로 인증한다. Subject
mapping은 서버 관리 secret/credential record와 등록된 principal의 관계이며 요청 payload가
아니다. #131은 Auth owner 아래 최소 validator/provisioning path를 구현한다. Operator는
기존 persisted membership/grant, Customer는 authenticated subject의 ownership/public booking
권한을 사용한다. Dev fixture name이나 M5 recovery identity를 production 증거로 사용하지 않는다.

Delegation issuance는 original Actor 인증과 명시적 scope 승인을 요구한다. Remote caller가
principal/tenant/role/allowlist를 제출해 자신을 provision하는 endpoint는 없다. Identity mapping의
운영 provisioning은 통제된 서버 관리 경로이고 broad administrator credential을 tool에
전달하지 않는다. #131의 최소 profile을 넘어 signup/federation/generic IdP를 구현하지 않는다.

| Server-owned field | 계약 |
| --- | --- |
| originalPrincipal / original credential reference | 인증된 individual subject. Delegate에게 원 credential 공개 금지 |
| delegationId / credential purpose | 유일한 opaque ID; MCP audience와 Product audience는 별개 값/credential. Token digest만 저장, secret은 응답/로그/metadata에 없음 |
| tenant / venue | 한 delegation은 한 verified Tenant/Venue. Customer는 active public Venue의 persisted ownership, operator는 current membership/grant로 derive. Tenant claim은 없음 |
| profile / actions / tool allowlist / read-write | Customer와 management 분리. Delegation 발급 당시 상한과 현재 Actor 권한의 교집합. 새 role/grant 획득으로 delegation 자동 확대 금지 |
| issuedAt / expiresAt / revokedAt | Delegation 최대 15분, expiry 경계 `now >= expiresAt`는 거부. Server clock 사용, caller timestamp 무시 |
| requestId / correlation / deadline | 매 request fresh server ID, immutable scoped handler context. RPC ID는 wire matching만 담당. ThreadLocal만으로 propagation 보장 주장 금지 |

Credential/delegation/current Actor 권한은 list/call admission, final Product dispatch, Product
credential validation, retrieval disclosure/result release 시 다시 확인한다. Revocation/권한 축소를
장시간 session/cache에 snapshot하지 않는다. Authority store unavailable은 fail closed다.
판정 시점은 해당 boundary의 current read다. 이후 revoke된 이미 admitted Product transaction을
소급 rollback한다고 주장하지 않는다. Revocation 뒤 새 admission은 거부하고 knowledge의
응답 직전 재검증은 이미 끝난 provider disclosure를 되돌리지 못한다.
Restart/state loss 시 기존 credential/delegation을 임의로 live 상태로 재구성하지 않는다.
Authoritative state를 복구했거나 새 Actor 승인으로 새 identity를 발급한 경우만 admission한다.

Downstream Product credential은 Auth가 original/delegation을 검증해 발급한 최대 60초의
좁은 audience credential이다. Product edge에서 허용 HTTP method/route/Venue/target와 grant를
확인하고 이후 기존 application authorization을 수행한다. MCP grant type에 대한 Product→AI
dependency를 만들지 않는다. HOLD Customer subject를 service subject로 바꾸지 않는다.
MCP bearer를 forwarding하지 않고 Product credential을 client에 노출하지 않는다.

Own-resource 제한은 credential을 principal ID로 바꾸는 것만으로 달성되지 않는다. 현재
exact GET은 같은 subject의 operator membership도 허용하고 response DTO에는 customer owner가
없다. #131의 Auth-owned consumer restriction은 Product `authorizeReservationRead`에 전달되는
persisted `ReservationAccessTarget.customerPrincipalId`에서 own-only를 강제해야 한다. #132가
operator membership도 가진 Customer subject로 다른 고객 예약을 읽으려는 경우를 실제 HTTP로
검증한다. MCP에서 owner를 추정하거나 DTO에 없는 값을 읽어 검증했다고 주장하지 않는다.
일반 original Actor의 기존 Product operator read 권한은 유지한다.

M5 [OperationsRecoverySecurity](../../backend/src/main/java/com/slotq/integration/operations/recovery/OperationsRecoverySecurity.java)/
[OperatorCredentials](../../backend/src/main/java/com/slotq/integration/operations/recovery/OperatorCredentials.java)와
[ScrapeCredential](../../backend/src/main/java/com/slotq/observability/web/ScrapeCredential.java)은
분리된 audience/store/matcher다. 같은 이름/tenant의 human operator라고 AI delegation이 생기지
않는다. 그 credential을 MCP/Product/corpus auth에 제출하는 substitution은 모두 거부한다.

## 4. Tool permission matrix

Profile은 새 TenantRole이 아니라 선택된 delegation capability다. 동일 subject가 membership을
갖고 있어도 Customer delegation으로 management tool을 사용할 수 없다. List에서 숨긴 tool도
direct call에서 동일하게 거부한다. Tool allowlist는 Product/corpus authorization을 대체하지 않는다.

| Tool | Actor/profile | Scope / read-write / confirmation | Authoritative permission / underlying capability | 금지 |
| --- | --- | --- | --- | --- |
| `reservation.get` | Customer | Verified Venue, exact Reservation, read, write confirmation 없음 | `GET /api/v1/venues/{venueId}/reservations/{reservationId}` + authenticated customer ownership. Product endpoint의 operator-read 가능성보다 tool은 좁음 | 다른 Customer, operator delegation을 통한 own-read 대체, tenant/venue escape |
| `reservation.hold` | Customer | Verified Venue + exact Slot, write, authenticated original의 exact confirmation 필수 | `POST /api/v1/venues/{venueId}/reservations/holds`, Product Customer subject/Slot ownership/capacity/policy, valid Product key 필수 | management profile의 대리 고객 생성, membership requirement 추가, cancel/confirm/Waitlist/recovery 확대, unconfirmed/altered intent |
| `management.reservations.list` | management: Owner/Manager/Staff | Granted Venue, Venue-local date/status, read, write confirmation 없음 | `GET /api/v1/management/venues/{venueId}/reservations`, current `requireVenueAccess`. Staff read와 server effective state/allowedActions 보존 | Customer direct call, 미할당 Venue, 다른 Tenant, write action dispatch |
| `knowledge.search` | Customer 또는 management | Customer `venue_public`, operator granted Venue의 `venue_public`/`venue_operator`; read, write confirmation 없음 | §8의 current corpus publication/visibility와 trusted access relation | global corpus search, client-selected tenant, withdrawn/superseded, authoring/upload, 자연어에서 얻은 permission |

Read에 별도 write confirmation이 없는 것은 무제한 consent가 아니다. Delegation issuance에서
client와 read scope를 승인한다. Tool schema에는 credential/principal/tenant/role/allowlist/baseURL가
없고 unknown/extra field를 거부한다. Dates, UUID, enum, positive partySize, query/excerpt/result
크기는 strict하게 제한한다. `reservation.hold`의 `intentId`, confirmation reference와 key는
§5의 server record와 일치해야 한다. Knowledge query 문자열은 untrusted data다.
Product management list가 tool result/read-size bound를 초과하면 safe limit category로 거부한다.
Empty/complete collection인 것처럼 일부만 반환하거나 Product에 pagination 계약을 추가하지 않는다.

## 5. Confirmation / Product idempotency / outcome unknown

### Product 계약 보존

- Namespace: `(persisted tenant, authenticated customerPrincipalId, exact opaque key)`.
- Fingerprint: semantic `(venueId, slotInventoryId, partySize)`. JSON serialization hash가 아니다.
- Reliability `IN_PROGRESS` insert, Reservation/Allocation, `COMPLETED + reservationId + completedAt`은
  하나의 Product transaction. Rollback은 reliability row도 제거한다.
- Retention 안 same-intent는 최초 Reservation identity/Location을 재사용하고 현재 effective
  state를 계산한다. 다른 fingerprint는 side effect 없는 `409 IDEMPOTENCY_KEY_REUSED`다.
- Successful `completedAt + 24h` **미만**에 replay, 경계부터 같은 key 문자열은 새 command
  identity 가능. Cleanup 지연은 이 의미를 연장하지 않는다. MCP는 `completedAt`를 변경하거나
  reliability table을 조회/노출하지 않는다. Existing HTTP optional-key는 유지한다.

### 승인과 intent 상태

#132는 좁은 HOLD intent/approval record를 durable하게 소유한다. Preparation과 approval은
original Actor credential로 인증된 control path이며 MCP tool allowlist 밖이다. AI delegation이
자기 approval을 발급할 수 없다. Generic workflow/새 approval UI/elicitation은 필요하지 않다.
Review representation은 exact Venue/Slot/partySize와 실제 HOLD effect를 original approver에게
표시한다. 승인 Boolean, client timestamp, LLM summary, RAG text는 증거가 아니다.

승인 binding은 `approver=originalPrincipal`, delegationId, persisted Tenant/Venue, tool/action,
exact Slot(그 stored resource 관계), material fingerprint, intentId, Product key, server expiry다.
Material change는 다른 intent/새 approval을 요구한다. Product가 Resource를 Slot에서 얻는
현재 API에 caller-controlled resource 필드를 추가하지 않는다.

서버가 intent별 fresh Product key를 준비하고 tool은 그 exact key를 필수로 제출한다. 첫
dispatch 직전 durable compare-and-set으로 `firstDispatchAt`과 immutable binding을 확정한다.
Concurrent confirmation reuse는 같은 intent/key에만 귀속하며 Product idempotency가 한 effect를
보장한다. Approval을 다른 intent/Venue/Slot/partySize/key/delegation/action에 사용하면 거부한다.
승인을 한 번 소비한 뒤 response loss 때문에 같은 intent 복구까지 금지하는 방식은 선택하지 않는다.

Confirmation validity는 최대 5분이다. Explicit same-intent retry는 live delegation과 유효
confirmation을 요구하며 **최초 dispatch + 최대 15분**의 absolute 종료를 넘을 수 없다.
Confirmation 갱신은 원 Actor의 새 exact approval을 요구하지만 최초 dispatch 시각/key를
바꾸지 않는다. Restart 시 durable binding을 복구할 수 있어도 작업을 자동 dispatch하지 않는다.
Clock regression/unknown dispatch timestamp/record loss는 write 복구를 fail closed한다.

Expired intent는 read-only reconciliation만 가능하다. 새로운 effect를 원하는 사용자는
unknown을 인지한 별도 명시적 intent와 fresh key를 승인해야 한다. Old key를 새 confirmation에
붙여 old intent의 Product retention을 연장하거나 자동 새 command로 만들 수 없다.
Terminal intent/approval reference는 firstDispatch + 25시간 이상 tombstone/binding으로 유지한
뒤 cleanup할 수 있다. Unknown/expired reference는 복원·재발급하지 않고 거부한다. 서버가
발급한 key를 client가 임의로 rebind하지 못하도록 #132가 검증한다.

### COMMIT 뒤 response loss

| 관찰 | 응답/복구 의미 |
| --- | --- |
| Admission/validation/permission 거부, Product로 미전송이 확인됨 | `not_dispatched`와 safe reason. Business rollback을 주장할 필요 없음 |
| Product success 응답 수신 | authoritative Reservation ID/Location/effective state. HOLD 생성 성공은 booking CONFIRM과 다름 |
| Product dispatch 뒤 timeout/disconnect/system failure로 결과 확정 불가 | `outcome_unknown`. COMMIT 가능. 새 key/자동 mutation 재시도 금지 |
| Unknown + known Reservation ID | 기존 exact GET으로 현재 state 확인. GET 404/실패만으로 최초 mutation 실패/rollback 확정 금지 |
| Unknown + no target ID | unknown 유지. 유효 absolute window 안 explicit same-key/same-intent retry만 가능 |
| Retry/confirmation/delegation expired | 새 dispatch 거부, known target read만 현재 read 권한 안에서 허용. 확인할 target이 없으면 unknown 유지 |

Product의 확정 validation/domain conflict와 system failure를 같은 재시도 결과로 합치지 않는다.
MCP response에는 dispatch category, known target reference, safe Product code와 correlation을
구분한다. 자연어 success/error 한 문장만으로 business outcome을 표시하지 않는다.

## 6. Timeout와 delayed admission gate

MCP request/transport budget과 handler deadline은 최대 30초, Product HTTP connect는 최대 2초,
response wait는 최대 15초, retrieval/provider는 최대 10초로 제한한다. 각각 더 짧은 남은
deadline을 따른다. 수치는 초기 bounded profile의 상한이며 SLA가 아니다. Timeout 뒤 실제
작업이 계속되면 resource permit을 유지하고 result를 unknown으로 처리한다.

**MCP admission → Product command admission 최대 60초**를 지원 조건으로 선택한다.
현재 topology/source 조사에는 HOLD queue/redelivery/durable task가 없지만 현재 설정만으로
이 최대 지연은 증명되지 않았다. #131/#132가 다음 evidence를 내기 전 write를 활성화하지 않는다.

1. Hidden SDK task/automatic HTTP retry/redirect replay, unbounded executor·ingress queue,
   broker/redelivery, restart replay가 없음. Rate/concurrency saturation은 immediate rejection.
2. Final dispatch 직전 live context/approval/absolute retry window와 remaining deadline 재검증.
   Product credential은 최대 60초, Product edge에서 expiry/current delegation/target 검증.
3. 실제 servlet/connection pool 대기, Product auth→application entry, MySQL lock/query/socket과
   transaction 종료를 유한하게 제한하는 configuration과 failure test. Client read timeout이나
   thread interrupt 하나를 server execution bound로 세지 않음.
4. Paused/saturated executor, connection exhaustion, DB lock/outage, response drop에서 admission
   timestamp와 durable Product state 기록. Expired queued context는 실행하지 않으며 unresolved
   worker가 permit을 잃지 않음. 지원 환경에서 bound를 보장할 수 없으면 write topology 기각.

First dispatch 후 15분 이내의 explicit retry와 60초 최대 admission 지연은 shared server clock의
최초 성공 `completedAt + 24h`보다 충분히 이르다. 실제 완료 시각을 몰라도 earliest dispatch를
보수적인 하한으로 사용할 수 있다. 이 bounded profile에는 24h를 넘는 delayed execution을
지원할 이유가 없으므로 새 Product command deadline/admission protocol을 추가하지 않는다.
긴 queue나 suspended request의 무제한 재개를 지원하려면 ADR 재검토가 먼저다. #130에서
존재하지 않는 delayed topology를 전제로 Product idempotency를 일반화하지 않는다.

## 7. Rate / audit / correlation

### Local quota

지원 instance scope는 한 live MCP instance다. #131은 principal/delegation/tenant/tool별
finite token bucket(rate와 burst)과 concurrency admission을 구현한다. Principal aggregate는
delegation 재발급으로 quota를 우회할 수 없다. Tenant와 tool은 trusted grant/registry에서
얻는다. `tools/list`도 principal quota를 소비하고 각 authenticated call attempt는 validation/
forbidden/retry를 포함해 세며 refund하지 않는다. Invalid token은 별도 bounded HTTP ingress
보호만 적용하고 임의 principal bucket을 만들지 않는다.

Retrieval/provider에는 별도 비용 bucket와 in-flight cap을 둔다. Saturation은 안전한 rate
category로 즉시 거부하고 queue하지 않는다. 각 layer의 positive finite config/default와 reset/
eviction/cardinality 제한은 #131의 구현 선택이며 metric label에 individual ID를 넣지 않는다.
Authority store 실패는 fail closed, local limiter 초기화/장애는 new admission 거부다. 이미
dispatch된 Product outcome은 바꾸지 않는다. Restart는 local budget을 reset할 수 있고 global
guarantee가 아니다. 복수 live instance로 capacity를 확장하거나 shared quota를 약속하면 새
decision이 필요하다. DB/shared mechanism/Redis 비교는 그때 수행한다.

### Audit

MCP audit는 allowlisted structured metadata를 bounded 비차단 sink로 전달한다. Durable delivery,
crash 후 완전성, 법적 tamper-proof audit를 보장하지 않는다. 기본 보존은 접근 제한된 운영
sink에서 7일이며 sink/rotation이 없으면 이 보존도 보장했다고 표시하지 않는다. #131은
보존 설정과 sink unavailable/drop count를 재현하는 regression과 실행 결과 요약을 남긴다. Sink 실패/queue full은 안전한
관측 failure signal을 만들지만 admission, Product transaction/응답 또는 confirmation 상태를
변경하지 않는다. Product mutation 재시도를 유발할 audit failure 응답을 만들지 않는다.

Required metadata는 server requestId, original principal reference, delegation reference,
trusted Tenant/Venue, tool/action, admission allow/deny와 stable reason, dispatch 여부,
outcome(success/denied/not_dispatched/unknown/unavailable), latency/timeout layer, confirmation/
intent reference, known Product target 및 response `X-Request-ID`, retrieval document/version
reference다. 원 credential/key 대신 opaque reference를 사용한다. Admission allow는 Product
success가 아니고 audit의 missing outcome은 rollback 증거가 아니다.

Product는 incoming `X-Request-ID`를 신뢰하지 않는다. #132는 MCP ID와 Product가 반환한 ID를
mapping하고 response loss라 Product ID를 못 얻으면 `unavailable`로 남긴다. Cross-boundary
trace parent를 자동 신뢰하는 새 Product trace contract를 만들지 않는다.

Token/credential/secret, raw idempotency key, contact/불필요한 PII, raw prompt/query/document,
excerpt, client-controlled arbitrary object, source URL의 query/credential, uncontrolled provider/
exception/SQL text는 audit·trace·metric label에서 제외한다. Sentinel credential/PII/prompt/document/
provider error를 넣은 success/deny/timeout/unknown 경로에서 redaction을 테스트한다.
Confirmation/intent는 §5의 durable state이고 M5 recovery audit는 기존 atomic contract다.
이들을 MCP telemetry sink로 대체하지 않는다.

## 8. Corpus / retrieval authority

### Ownership / authoring / lifecycle (#133)

#133의 application/persistence, revision arbitration, bounded seed와 #134 metadata handoff는
[Tenant 문서 수명주기 구현 계약](knowledge-corpus.md)에 기록한다.

Knowledge owner가 document/version/publication metadata와 자신의 persistence/migration을
소유한다. Product JPA entity/repository/table을 공유하지 않는다. Auth/active Venue는 공개
계약으로 조회한다. BookingPolicy version과 policy 설명 document version은 다른 identity다.

Authoring은 원 Owner 또는 assigned Manager의 current `requireVenueConfigurationAccess`를
검증하는 통제된 manifest/ingestion adapter다. Customer/Staff/delegate/M5 credential은 authoring
권한이 없다. Ingest/publish/new-version/withdraw/re-index 모두 같은 trusted scope를 사용한다.
Authentication은 #131의 Auth-owned original Actor validator를 재사용하므로 선택한 계획에서
**#133의 선행은 #130 + #131**이다. #131은 corpus command를 구현하지 않는다. MCP upload,
새 editor UI나 filename/source URL 기반 authority는 없다.

Version은 immutable content digest와 source reference를 가진다. Manifest는 synthetic seed,
Tenant/Venue/document/version/visibility/content identity를 재현한다. External fetch/crawler는
기본 ingestion이 아니다. Publication metadata는 exact current version과 visibility를 결정한다.

| 상태/command | 계약 |
| --- | --- |
| staging/validation | 아직 retrieval-ineligible. Bounded synchronous 또는 staged ingestion. 실패가 active publication을 바꾸지 않음 |
| publish | 검증된 exact version을 authoritative metadata transaction으로 active publication에 연결 |
| update/new version | 새 immutable version 준비 성공 후 current pointer 교체와 old version supersession을 함께 판정. Failed update는 기존 version 유지 |
| superseded | 역사적 source/version은 보존 가능하지만 retrieval-ineligible. Current로 relabel 금지 |
| withdraw | Publication authority에서 즉시 ineligible. Index/provider residue는 이를 되살릴 수 없음 |
| delete/cleanup | Logical withdrawal과 physical index/provider deletion 분리. 실제 external copy 삭제 미확인이면 hard deletion 보장 없음 |
| re-index | Exact Tenant/Venue/document/version/content identity의 derived work. Completion은 current publication을 바꿀 권한 없음 |

Update/withdraw/re-index의 arbitration은 current metadata version/CAS 또는 동등한 scoped
transaction으로 구현한다. Late work는 obsolete artifact만 남길 수 있고 current pointer를
rollback하지 못한다. Metadata DB와 external index/provider I/O를 원자적 transaction으로
표현하지 않는다. Async retry backlog/long-running fan-out이 실제 필요하면 #133의 기존
mechanism gate에서 재검토하며 Kafka를 기본 채택하지 않는다.

### Visibility / eligibility / result (#134)

- `venue_public`: 해당 active public Venue의 published 안내. Authenticated Customer의 좁은
  delegation이 접근할 수 있다. Venue→Tenant는 trusted active Venue lookup으로 derive하며
  public Venue ID를 corpus 전체 권한으로 사용하지 않는다.
- `venue_operator`: current assigned Owner/Manager/Staff와 management delegation만 접근.
  Authoring은 Owner/Manager로 제한한다. Global/public corpus나 Customer-private 문서는 M6 제외.

Retrieval port는 **provider/query disclosure 전** live Actor/delegation, Tenant/Venue/visibility와
published eligibility를 강제한다. 다른 tenant의 문서를 검색/embedding provider에 보내고
나중에 filtering하는 방식은 금지다. Query embedding과 document embedding 각각 외부
provider로 전송할 exact data 범위/보존 조건을 비교 evidence에 기록한다. Default comparison은
synthetic corpus와 local embedding candidate로 시작할 수 있다. 실제 고객 PII는 넣지 않는다.

External provider 사용은 별도의 명시적 data disclosure 승인과 고정 endpoint/secret 설정을
요구한다. Raw query/document를 audit에 복사하지 않는다. Approval이 없으면 local candidate를
사용하며 provider 수나 vector DB를 늘리지 않는다. Query text를 임의 URL로 실행하지 않는다.

Final result observation point는 **응답 materialization 직전의 scoped current metadata read**다.
Exact version/content/visibility와 live grant를 다시 검증하고 withdrawn/superseded candidate를
제거한다. Revalidated immutable version에서 excerpt를 구성한다. 그 observation 뒤의 withdraw를
이미 전송된 응답에서 소급 제거한다고 주장하지 않는다. Query 중 update/withdraw를 이 read
앞/뒤로 나눠 deterministic test한다. Scope 재검증 failure는 unavailable/deny, global fallback 없음.
여기서 current는 query 시작 때의 MySQL RR snapshot/ORM cache가 아니다. #133/#134는 새 read
transaction 또는 locking current read 등 실제 committed metadata를 관찰하는 경계를 검증한다.

Result는 bounded source/document/version/visibility reference와 excerpt/rank를 연결한다.
Source는 provenance이며 사실/권한 보증이 아니다. Allergy 안내는 source/version의 안내로만
표시하고 safety guarantee를 생성하지 않는다. RAG/tool 자연어는 untrusted content로 구조화
결과와 분리하며 authorization/allowlist/tenant/confirmation/domain policy를 수정할 수 없다.

| 결과 category | 의미 |
| --- | --- |
| `evidence` | 현재 eligible source/version에 근거한 bounded retrieval 결과. Product policy 적용 결과가 아님 |
| `no_answer` | Eligible corpus에서 query를 완료했지만 반환할 근거 없음 |
| `insufficient` | 관련 source는 있지만 요청을 뒷받침하기에 oracle/coverage 근거 부족. 추측으로 채우지 않음 |
| `unavailable` | Metadata/index/provider/deadline 실패로 판정 불가. 빈 결과/known no-answer로 표시하지 않음 |

Caller가 requested scope 밖의 document ID/count/content를 알 수 있는 diagnostic도 금지한다.
Provider timeout은 tenant/global fallback을 허용하지 않는다. Separate public retrieval HTTP API는
필요 없으며 `knowledge.search`는 #131 registry/common controls와 knowledge public port를 연결한다.

## 9. #134 comparison oracle와 검증

Canonical manifest를 고정하고 두 candidate를 실제 실행한다. A는 lexical/full-text baseline,
B는 실제 document/query vector를 생성하는 pinned embedding model/service + index다.
Random/fixed/hash vector, mock만 실행, dependency 설치는 B 검증이 아니다. Vector DB는
필수가 아니며 같은 approved eligible corpus와 query/oracle, result bounds를 사용한다.

| 검증 항목 | Required fields/판정 |
| --- | --- |
| Provenance manifest | source revision, corpus/query/oracle digest, seed, exact immutable versions/content hashes, environment/CPU/RAM, candidate/model/tokenizer/library version, chunk/index config, provider disclosure approval/reference |
| Query oracle | queryId, trusted Actor profile/Tenant/Venue/visibility, known-answer/no-answer/insufficient/unavailable case, expected source/document/version 또는 allowed set, forbidden source set/lifecycle negative |
| Per-query result | candidate/run/query ID, category, ranked source/document/version/rank/score, bounded synthetic result, source-hit 판정, safety failure, failure category, executed timestamps/duration |
| Cost/resource observation | build/index latency, per-query latency, repetitions/warm/cold distinction, CPU/memory/storage sampling method, provider invocation/token/cost 관측 또는 `unavailable`/`not_applicable` |
| Recalculation | 실행 결과→summary script/command, counts/denominator, latency aggregation, source/version hit, no-answer/insufficient 판정, lifecycle/tenant violations, limitations |

Per-query/resource raw output이 재계산에 필요하면 gitignored 실행 경로에서 생성한다. Repository에는
canonical manifest/oracle, 재실행 가능한 harness/config, 선택 근거와 결과 요약을 남기며 run별
raw artifact 보존 자체를 완료조건으로 삼지 않는다.

Oracle는 explicit labelled expected evidence/coverage로 no-answer와 insufficient를 구분한다.
임의 score threshold를 production accuracy 기준으로 발명하지 않는다. Safety(tenant/visibility/
lifecycle/source/version) 위반은 candidate 탈락 조건이다. Quality와 latency/resource/cost는
같은 작은 seed set에서 상대 비교하며 production quality/SLA로 일반화하지 않는다. 관측하지
못한 provider 비용/resource는 0이 아니라 unavailable이다. #134는 lexical default/embedding
default/근거 있는 좁은 hybrid 중 결과로 선택하고 rejected candidate 이유를 기록한다.

필수 query/oracle에는 tenant별 known-answer, 다른 tenant/venue parameter, no-answer,
insufficient, superseded, withdrawn+stale index, query 중 update/withdraw, malicious instruction,
provider unavailable/timeout을 포함한다. #135는 두 실제 candidate를 최신 source에서 실행하고 recalculation 경로와 결과 요약을
검증하며 comparison을 새로 설계하거나 M8 generic evaluation framework를 만들지 않는다.

## 10. Threat model / adversarial handoff

Selected topology의 공격면은 MCP HTTP ingress/credential, trusted delegation→Product HTTP,
approval control path, scoped corpus/index/provider와 metadata audit다. OAuth redirect/discovery와
MCP Tasks는 사용하지 않는다. Generic malicious-doc test는 deterministic client가 검색 뒤
forbidden call을 시도하는 것으로 충분하고 Agent Runtime이 필요하지 않다.

| 시나리오 / threat | 방어 계약 | Primary test owner (#135는 전체 통합) |
| --- | --- | --- |
| 1. Customer → management direct tool / forbidden invocation | §4 independent direct-call authorization, current Product grant | #131/#132 |
| 2. Caller tenant/role/allowlist 조작 / delegated escalation·tenant escape | §3 server record + current intersection, §4 strict schema, Product derivation | #131/#132/#134 |
| 3. M5 recovery credential 또는 scrape credential 제출 / misuse | §3 audience/store/matcher 분리, no recovery tool | #131 |
| 4. Application port로 HTTP security/wire validation 소실 | §2/§3 HTTP invocation 고정, application port 미채택 | #132 architecture + real HTTP/MySQL |
| 5. Broad service credential이 original Actor 대체 / confused deputy·passthrough | §3 distinct narrowed credential + same individual subject, arbitrary origin/redirect 금지 | #131/#132 |
| 6. Confirmation을 다른 Venue/Slot/key/party/delegation에 재사용 | §5 exact binding/durable CAS + Product fingerprint | #132 |
| 7. HOLD COMMIT 뒤 response loss / duplicate retry | §5 unknown, immutable same key, known target GET, no automatic retry | #132 real response-drop + durable state |
| 8. Old approval/retry/renewal로 Product 24h retention 연장 | §5 earliest dispatch +15m, immutable key/binding/tombstone, expired no dispatch | #132 before/at/after 24h + fresh-approval attack |
| 9. 존재하지 않는 delayed execution 때문에 Product protocol 추가 | §6 bounded synchronous support gate, unsafe queue 기각, no new deadline protocol | #131/#132 configuration + delayed worker/DB tests |
| 10. Single instance에 Redis 추가 | §7 local quota scope, shared topology 미선택 | #131 dependency/config review |
| 11. Shared quota를 local limiter로 global 보장 | §7 single live instance 지원, restart/local limitation 명시 | #131 saturation/accounting + #135 deployment review |
| 12. RAG 문서가 권한 상승/approval 지시 / parameter substitution | §4/§8 untrusted content, registry/schema/approval records만 authority | #134 retrieval → forbidden call; #132 binding |
| 13. Withdrawn/superseded index가 authority가 됨 / stale work | §8 metadata publication/CAS + final current eligibility | #133/#134 concurrency/stale work |
| 14. Raw credential/prompt/document/provider error audit leakage | §7 allowlist/redaction/bounded safe categories, sink failure와 Product outcome 분리 | #131/#132/#134 sentinel + sink failure |
| 15. M7 Agent Runtime/Router가 M6에 선도입 | §1/§9 bounded tools/comparison만, no model routing/workflow | #131~#134 architecture, #135 closure |
| Session hijack / concurrent context mix | §3 every request auth, session-ID 자체 권한 아님, immutable context, current expiry/revoke | #131 concurrent profiles + revoke |
| HTTP DNS rebinding/Origin abuse / SSRF | §3 Host/Origin/TLS, fixed Product/provider destinations, no arbitrary URL/redirect forwarding | #131/#134 |
| Provider로 unauthorized corpus disclosure | §8 pre-query scope + approval + exact version, response-only filter 금지 | #134 provider spy/real candidate verification |
| Quota exhaustion/timeout surviving work | §6/§7 bounded pools/no queue, permit은 실제 종료까지 유지 | #131/#132/#134 |

## 11. Dependency / implementation gate / 검증 상태

| Issue | 선행 | 소유하는 contract/verification | 소유하지 않는 책임 |
| --- | --- | --- | --- |
| [#131](https://github.com/krestar/slotq/issues/131) | #130 | Auth-owned minimum original Actor mapping/delegation, independent MCP/Product credential scopes, protocol/registry/common controls, exact Java25/Boot4.1.1 interoperability gate | Product business, confirmation, corpus, shared quota topology |
| [#132](https://github.com/krestar/slotq/issues/132) | #130 + #131 | 실제 세 HTTP tool, durable intent/approval, same-key/reconciliation/unknown, §6 admission bound + real MySQL/response drop | Domain/idempotency redesign, reliability lookup API, auto retry runtime |
| [#133](https://github.com/krestar/slotq/issues/133) | #130 + #131 | Shared Auth validator를 쓰는 original operator authoring, document/version/publication/stale work/seed | MCP upload, concrete retrieval algorithm |
| [#134](https://github.com/krestar/slotq/issues/134) | #131 + #133 (#130 계약) | Scoped retrieval/index/knowledge bridge, actual A/B verification/default selection | Product authority, common auth 재구현, vector DB 선채택 |
| [#135](https://github.com/krestar/slotq/issues/135) | #131~#134 완료 | Two profiles/four tools fresh integration, criterion→owner→verification→test/harness/CI reference→revision→limitation, M6 closure | 새 security/architecture policy, M7/M8 |

#133 본문의 conditional dependency gate에 따라 shared original Actor validator를 재사용하는
선택을 확정했다. Corpus 생산 기능을 #131로 옮기지 않으며 #133은 이 선행이 준비된 뒤
production authoring을 연결한다. M6 전체 완료는 #135의 실제 execution/evidence 전에는 선언하지 않는다.

#130에서 수행한 검증은 current source/Issue/ADR/공식 자료 대조, 문서 link/reference consistency,
15개 self-audit와 관련 기존 architecture tests다. Java 25.0.4에서
`gradlew.bat test --tests 'com.slotq.architecture.*' --offline --no-daemon --console=plain`은
4 suite/27 case PASS, failure/error/skip 0이다. Production source/schema/dependency/SDK를
변경하지 않았고 spike/새 test support는 추가하지 않았다. Full Backend/Frontend build와
MCP client/provider/MySQL integration은 이 문서 변경에서 실행하지 않는다. Exact artifact
resolution/start/interoperability와 60초 admission bound는 후속 **활성화 조건**이며 현재 PASS
evidence로 표시하지 않는다. 구현 gate의 실패는 owner가 해결하거나 해당 topology를 기각해야
하며 #135에서 임시 정책으로 덮지 않는다.
