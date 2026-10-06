# MCP authenticated access와 Product tools (#131/#132)

ADR-0009의 한 live Product JVM에 opt-in으로 활성화한다. 기본 artifact의 `/mcp`는 404이며
`relay`, `consumer`, `quiesced` 등 Product 이외 role에서도 활성화하지 않는다.
Production registry는 `reservation.get`, `reservation.hold`, `management.reservations.list`를
명시적으로 등록한다. Knowledge bridge는 #134 ownership이다.

## 활성화 조건

같은 JVM의 TLS port를 명시하고 기존 Product datasource 설정에 다음 상한을 적용한다.
개발 credential bootstrap이나 M5 recovery/scrape credential은 MCP identity가 아니다.

```properties
slotq.mcp.enabled=true
slotq.events.runtime-role=product
server.port=8443
server.ssl.enabled=true
# 기존 deployment의 TLS key-store/secret 설정 사용
slotq.mcp.origin=https://slotq.example:8443
slotq.mcp.product-origin=https://localhost:8443
spring.datasource.hikari.connection-timeout=2000
spring.datasource.hikari.data-source-properties.socketTimeout=2000
```

Product origin은 같은 port의 HTTPS loopback origin으로 고정한다. Certificate는 해당
loopback host를 검증할 수 있어야 한다. Caller URL, redirect, credential forwarding API는 없다.
Servlet connector는 maxThreads 64 / minSpareThreads 16 / maxConnections 128 / acceptCount 16,
connection/upload timeout 2초를 사용한다. MCP ingress는 기본 8이며 최대 32다. 비차단 body
read와 async request deadline을 사용하므로 MCP가 Product servlet thread를 기다리며 점유하지 않는다.
Authority connection acquisition/socket이 2초를 넘거나 TLS가 없으면 activation을 거부한다.
Auth query/transaction은 2초이며 DB failure는 safe unavailable이다. 수치는 SLA가 아니다.

| 설정 | 기본값 / 상한 |
| --- | --- |
| `slotq.mcp.handler-budget` | `PT30S` / 30초. 요청 body/admission부터 계산 |
| `slotq.mcp.workers` | 8 / 32, `SynchronousQueue`와 즉시 reject |
| `slotq.mcp.ingress` | 8 / 32, 별도 request executor와 permit |
| `slotq.mcp.sessions` | 512 / 4096, principal당 4, delegation expiry까지 |
| HTTP ingress rate/burst | instance 전체 50/초, burst 100. Invalid bearer도 포함 |
| `slotq.mcp.quota.rate` / `.burst` / `.concurrency` | 각 layer 5/초 / 20 / 4, positive finite |
| `slotq.mcp.quota.cardinality` | 4096 / 65536 |
| Input / structured output / wire response | 64 KiB / 64 KiB / 128 KiB, JSON nesting 16 |
| Product connect / response / credential | 2초 / 15초 / 최대 60초, remaining deadline으로 축소 |
| Retrieval handler | 최대 10초, remaining request deadline으로 축소 |

Principal/delegation/tenant/registered tool/resource bucket은 독립 aggregate다. Unknown tool은
고정 `unknown` bucket을 사용한다. Authenticated list/call은 validation/forbidden/explicit retry도
소비하며 refund하지 않는다. Limiter unavailable/clock regression/cardinality saturation은 새
admission을 거부한다. In-flight 또는 아직 충전되지 않은 bucket은 eviction하지 않는다.
Permit은 실제 handler runner의 finally에서 반환한다. Timeout/disconnect 뒤에도 살아 있는
작업을 사라진 것으로 세지 않으며 자동 재시도, cancel-complete 또는 rollback을 주장하지 않는다.
Restart는 local quota/session을 reset할 수 있다. Global/shared quota와 multiple live MCP instance는
지원하지 않으며 필요해지면 ADR 재검토가 먼저다. Redis는 사용하지 않는다.

## 서버 관리 authentication / delegation

Auth-owned `ActorAccessService`가 등록된 individual principal의 original opaque credential을
provision한다. 서버 관리 adapter가 인증된 original Actor의 명시적 승인을 받은 exact Venue,
profile/action/tool set과 최대 15분 validity로 `approveDelegation`을 호출한다. Public provisioning
endpoint, signup/IdP 또는 caller claim 기반 issuance는 없다. Provisioned secret은 해당 통제된
delivery 경로에서 한 번 전달하고 로그/문서에 복사하지 않는다. Original credential은 delegate에게
전달하지 않는다. DB에는 digest와 UUID reference만 저장하며 MCP/ORIGINAL/PRODUCT audience와
값을 분리한다. Auth table의 쓰기 권한은 이 통제된 owner에만 부여한다.

Credential/delegation revoke는 UUID 기반 서버 관리 API다. 매 boundary는 별도 current read
transaction을 사용해 expiry/revocation/original credential와 현재 membership/grant를 검증한다.
Customer delegation은 active public Venue의 persisted Tenant를 derive한다. Management history
read는 기존 Product와 같이 membership/grant를 사용하며 Venue 활성 상태를 새 조건으로 만들지 않는다.
Corpus authoring validator는 original credential과 current configuration permission/active Venue를
요구한다. Delegation 확대/renewal은 기존 identity를 수정하지 않고 원 Actor의 새 승인을 요구한다.
Record loss/expiry/revoke는 fail closed하며 이전 secret/identity를 임의 재구성하지 않는다.
Expired/revoked Auth record의 운영 cleanup은 reference를 임의로 live로 복원하지 않는 통제된
운영 작업이다. 이번 foundation에는 일반 identity lifecycle/retention 관리 UI를 추가하지 않는다.

## 후속 integration contract

- **#132**: `McpRegistrations` 한 composition root에서 `ToolDefinition`을 명시적으로 등록한다.
  `RequestContext`를 executor 사이에 직접 전달하고 final dispatch 직전에 `revalidate`한다.
  `ProductHttpBinding.prepare`는 fixed-origin URI, distinct scoped credential, bounded connect/response
  budget을 제공한다. Concrete authenticated HTTP client/response mapping은 #132가 소유한다.
  `ProductCredentialAccess`는 exact GET route/Reservation target 또는 management list route를
  Product edge에서 재검증한다. Customer는 persisted `ReservationAccessTarget.customerPrincipalId`로
  own-only를 강제하므로 같은 subject의 operator membership으로 우회할 수 없다.
  `prepareHold`도 exact material에 묶인 별도 Product credential을 제공한다. Production root는
  아래 bounded write profile을 검증하지 못하면 startup을 거부한다.
  Client response timeout을 remote Product execution 종료 증거로 사용할 수 없다.
- **#133**: `ActorAccess.validateOriginal` / `requireOriginalConfigurationAccess`를 재사용한다.
  Delegate/MCP/Product/M5 credential은 original authoring identity가 아니다. Corpus command/table은
  Auth/MCP에서 구현하지 않으며 corpus publication과 current metadata read는 knowledge owner 책임이다.
- **#134**: `ActorAccess.revalidate`와 immutable context, explicit registry/schema, retrieval resource
  bucket과 최대 10초 deadline을 재사용한다. 한 `knowledge.search` 등록의 immutable profile→action
  permission map으로 Customer/management를 각각 허용하고 direct invocation에서도 해당 profile의
  current action/tool grant를 검사한다. Provider disclosure와 result materialization 전에 scoped
  current metadata/Actor read를 수행한다. 실제 retrieval/provider/index와 corpus 권한은
  해당 handler/public port가 강제한다. Tool allowlist가 그 권한을 대신하지 않는다.

`ToolOutcome`은 bounded structured content와 dispatch/known target/Product response request ID,
confirmation/intent/document/version UUID reference, stable failure/timeout layer를 전달한다.
Output schema는 success/error 양쪽을 포함해 등록해야 한다. Failure는 `isError=true`이고 known
reference는 result `_meta`와 audit에 보존한다. Uncontrolled exception은 generic unknown이며 raw
provider/SQL text를 응답이나 audit에 복사하지 않는다. Common timeout은 실행 가능성이 있는
handler를 unknown으로 처리한다. 이후 business outcome 판정/복구는 #132/#134의 좁은 책임이다.

Schemas는 closed JSON Schema 2020-12이며 string/array의 finite bounds를 선언한다. Remote `$ref`,
dynamic ref와 schema ID는 지원하지 않는다. SDK validator는 tool/input/output별로 분리해 finite
schema cache와 hash-key collision 격리를 유지한다. Registry/common layer는 concrete Product/
retrieval implementation을 참조하지 않는다. Product/Auth는 MCP를 역참조하지 않는다.

## Audit와 protocol limitation

Audit는 bounded queue(기본 256)의 daemon sink이며 caller thread는 offer만 수행한다. Success,
deny, unknown, timeout과 typed opaque reference만 기록한다. Credential/key/PII/prompt/query/document/
excerpt/client object/exception/SQL은 payload에 없다. Incoming request/trace ID도 authority가 아니다.
`slotq.mcp.audit.dropped`, `.failed`, `.delivered`는 individual label이 없는 관측 수치다.
Queue full/sink unavailable은 business result나 이미 committed effect를 뒤집지 않는다.

기본 sink는 기존 allowlisted console 경로이므로 운영 log sink가 없으면 보존을 보장하지 않는다.
접근 제한된 디렉터리를 명시하면 local metadata sink를 사용할 수 있다.

```properties
slotq.mcp.audit.directory=/protected/slotq/mcp-audit
slotq.mcp.audit.retention-days=7
slotq.mcp.audit.daily-bytes=1048576
slotq.mcp.audit.capacity=256
```

File sink는 UTC 기준 오늘을 포함한 7일, 하루 1 MiB 기본 상한이며 size/permission/storage failure는
failed count를 증가시킨다. 다른 파일을 삭제하지 않는다. 운영자가 디렉터리 접근 제어를 설정한다.
Crash 후 완전성, durable business ledger, tamper-proof audit 또는 실제 disk I/O 종료를 보장하지 않는다.

Target은 MCP `2025-11-25`, initialize/initialized lifecycle, tools only, Streamable HTTP다.
POST의 JSON response를 사용하고 optional GET SSE stream은 405다. Sessions는 authority가 아니며
every request의 credential/principal/delegation에 bind된다. Initialize의 requested revision이 지원되지
않아도 서버의 유일한 supported revision `2025-11-25`를 반환한다. Client가 반환된 revision을
수용하면 initialized와 이후 요청에 그 revision을 사용하고, 수용할 수 없으면 disconnect한다.
이는 다른 revision의 runtime semantics를 지원한다는 뜻이 아니다. Client capabilities는 bounded
metadata object로 받아들이지만 authority로 쓰거나 optional client feature를 호출하지 않는다.
Server capability는 `tools: {listChanged: false}`만 광고한다.
Missing session은 400, 종료/unknown/다른 delegation의 session은 404다. 종료 후에는 새 initialize가
필요하다. GET/DELETE를 포함해 unsupported protocol header는 400이며 받아들일 수 없는
notification/response와 malformed RPC도 HTTP error로 거부한다.
Tasks, roots, sampling, elicitation, resources/prompts, resumable/redelivery, OAuth discovery/login,
최신 stateless/per-request protocol은 광고하지 않는다. Stable SDK client의 test transport도
resumption/auth retry handler를 사용하지 않는다. Production adapter는 SDK scheduler/transport
executor를 활성화하지 않으며 retry/redirect/replay를 구현하지 않는다.

[실제 검증/evidence](../experiments/mcp-foundation/2026-10-04/README.md)와
[PR #137 negotiation 보정 검증](../experiments/mcp-foundation/2026-10-05-negotiation/README.md)을 참고한다.

## Product write 활성화 profile

기본 Product에는 이 설정을 자동 적용하지 않는다. Co-located MCP의 production root는 아래
profile을 fail closed로 검사한다. 기존 ordinary Product API의 optional key/DTO/domain 계약은
유지한다. TLS loopback 서버 인증서는 JVM trust store 또는 서버 관리 `SSLContext`로 신뢰해야 한다.
Arbitrary origin, redirect, token passthrough, HTTP 자동 retry는 지원하지 않는다.

```properties
spring.datasource.hikari.connection-timeout=2000
spring.datasource.hikari.data-source-properties.connectTimeout=2000
spring.datasource.hikari.data-source-properties.socketTimeout=2000
spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2, lock_wait_timeout=2, wait_timeout=5, max_execution_time=2000
spring.transaction.default-timeout=10s
spring.jpa.properties.jakarta.persistence.query.timeout=2000
```

JVM 시작 시 `-Djdk.httpclient.disableRetryConnect=true`와
`-Djdk.httpclient.enableAllMethodRetry=false`를 지정한다. 두 번째 값은 기본 false이지만 명시를
권장한다. Single-host `jdbc:mysql://`를 사용하고 URL에서 timeout/reconnect 속성을 덮어쓰지
않는다. `autoReconnect`/`autoReconnectForPools`는 false여야 한다. Session row/metadata wait는
최대 2초, read SELECT는 최대 2,000ms, idle session은 최대 10초, default transaction timeout은
최대 10초다. Hikari acquisition/connect/socket은 각각 최대 2초다. Deployment는 startup 검증
후 이 pool/session 설정을 임의 변경하지 않는다.

Client response budget은 server 종료 보장이 아니다. Auth가 발급한 Product credential UUID의
expiry와 current original Actor/delegation/operation/target을 Product application 진입 직전,
HOLD Slot lock 이후 다시 검사한다. HOLD partySize/key digest도 일치해야 한다. 일반 Product
principal에는 이 guard를 적용하지 않는다. Credential은 MCP admittedAt+handler budget
(최대 30초) 이전에 만료하며, local monotonic expiry도 검사한다. Authentication 후 61초 정지한
request도 재개 시 fail closed이며 별도 caller deadline protocol은 없다.

Auth의 bounded local exchange reference는 인증 증거가 아니며 Product filter의 실제 finally까지
현재 Auth read와 servlet 작업을 추적한다. HTTP timeout/interrupt 뒤 adapter가 그 종료를 기다리는
동안 MCP runner가 permit을 유지한다. 아직 Product에 도달하지 않은 credential은 expiry 후
재개할 수 없다. Local reference는 process restart로 복원하지 않으며 durable dispatch/redelivery는
없다. VM/DB 전체 정지 중 물리적인 종료 시간을 주장하지 않는다. 재개 후 expiry와 실제 종료를
확인하며, 살아 있는 Java work는 종료로 계상하지 않는다.

## HOLD review / approval / 명시적 retry

서버 관리 adapter가 인증된 original Actor의 통제된 UI/채널에서 `HoldApprovals.prepare`를
호출한다. 반환된 exact review를 보여준 뒤 같은 original Actor credential로 `approve`를 호출한다.
이는 공개 HTTP endpoint나 MCP approval tool이 아니며 public onboarding/approval UI는 제공하지
않는다. Caller Boolean, 자연어, RAG content, MCP audit는 승인이 아니다.

`mcp_hold_intents`에 original approver, delegation, server-derived Tenant/Venue, tool/action,
Slot, partySize, server-generated immutable Product key, prepared expiry를 저장한다. Slot→Resource
관계와 Product fingerprint의 최종 authority는 기존 Product HTTP command다. 승인 UUID는
`mcp_hold_confirmations`의 immutable intent FK와 approval/expiry를 참조한다. MCP HOLD의 closed
arguments는 `intentId`, `confirmationId`, `slotInventoryId`, `partySize`, `idempotencyKey`이며
모두 저장된 review와 정확히 일치해야 한다. Venue/tenant/role/action/tool 입력은 허용하지 않는다.

준비/승인은 최대 5분, 최초 dispatch는 row lock/CAS로 한 번만 저장하고 명시적 retry는 그 시각부터
최대 15분이다. 승인 renewal은 같은 intent/key/firstDispatch를 보존한다. 새 intent는 새 key를
발급하므로 기존 key의 retention을 연장하는 fresh approval을 만들 수 없다. MCP approval/intent
rows는 cleanup 없이 계속 보존하여 최소 25시간 tombstone 계약을 충족한다. 운영 cleanup/lifecycle
UI는 이번 범위에 없다. 데이터 접근은 MCP integration owner의 두 table로 제한한다.

각 호출은 Product HTTP request 한 번뿐이다. Product의 `(tenant, customer, key)` namespace,
`(venue, slot, partySize)` fingerprint, Reservation/Allocation/idempotency 동일 transaction,
successful `completedAt + 24h` retention을 그대로 사용한다. Product reliability table lookup은 없다.
Outcome unknown이면 자동 retry/key 교체를 하지 않는다. Location 또는 성공 DTO의 known target은
durable intent에 기억하고 `_meta.knownTarget`으로 제공하며 `reservation.get` exact read로 확인한다.
Target을 모르면 unknown을 유지한다. 다른 ID의 GET 404는 mutation failure의 근거가 아니다.

[#132 실제 gate·Product fault·최종 검증 기록](../experiments/mcp-product-tools/2026-10-05-application-guard/README.md)을 참조한다.

## Knowledge search

같은 explicit registration root에 `knowledge.search`가 포함된다. Original Actor가 승인한
Customer `KNOWLEDGE_PUBLIC` 또는 management의 public/operator action과 tool allowlist가 필요하다.
입력은 `query`(최대 512 character), optional `limit`(1~5)뿐이다. Scope는 delegation/catalog에서
derive하며 corpus upload/tenant selector/provider URL/answer generation endpoint는 없다.
Default는 local lexical retrieval이다. Excerpt는 untrusted source data이며 HOLD approval 또는
Product 권한을 부여하지 않는다. Strict result/failure, final publication observation, audit와
한계는 [검색 계약](../architecture/knowledge-retrieval.md), 실제 embedding 비교 재현은
[comparison runbook](../experiments/knowledge-retrieval/README.md)을 따른다.
