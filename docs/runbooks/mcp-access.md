# MCP access foundation (#131)

ADR-0009의 한 live Product JVM에 opt-in으로 활성화한다. 기본 artifact의 `/mcp`는 404이며
`relay`, `consumer`, `quiesced` 등 Product 이외 role에서도 활성화하지 않는다. Production
registry는 현재 비어 있다. 실제 Product tools는 #132, knowledge bridge는 #134가 등록한다.

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
  현재 read binding만 존재한다. Write credential/tool은 #132의 confirmation, intent/idempotency와
  실제 HTTP/DB/paused/saturated/response-loss 최대 60초 admission gate 전에는 활성화하지 않는다.
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
every request의 credential/principal/delegation에 bind된다. Unsupported revision/capability는 거부한다.
Missing session은 400, 종료/unknown/다른 delegation의 session은 404다. 종료 후에는 새 initialize가
필요하다. GET/DELETE를 포함해 unsupported protocol header는 400이며 받아들일 수 없는
notification/response와 malformed RPC도 HTTP error로 거부한다.
Tasks, roots, sampling, elicitation, resources/prompts, resumable/redelivery, OAuth discovery/login,
최신 stateless/per-request protocol은 광고하지 않는다. Stable SDK client의 test transport도
resumption/auth retry handler를 사용하지 않는다. Production adapter는 SDK scheduler/transport
executor를 활성화하지 않으며 retry/redirect/replay를 구현하지 않는다.

[실제 검증/evidence](../experiments/mcp-foundation/2026-10-04/README.md)를 참고한다.
