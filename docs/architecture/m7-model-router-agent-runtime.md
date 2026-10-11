# M7 Model Router & Agent Runtime 계약

> 상태: [#151](https://github.com/krestar/slotq/issues/151)의 공통 계약과 후속 decision 기준.
> #152의 bounded Router/provider 비교는 [구현·실험 기록](../experiments/m7-model-router.md)을 따른다.
> #153의 controlled process-local Runtime은 [ADR-0011](../adr/0011-m7-process-local-agent-runtime.md)을 따른다.
> 실제 세 Product/MCP flow와 durable 판정은 #155/#156의 후속 구현·검증 대상이다.
>
> 확인일: 2026-10-11. M6 source/Issue 설계 기준 revision은
> `047bea6235eae08fca0a1cc02388c4a86c1b60ae`이며, #152 구현 기준은 #151/PR #158을 포함한
> 최신 main `03a88be6c67140c6cace981fbbdcc8a88806500a`이다. #153 구현 기준은 #152를 포함한
> `7c51b17b029f67ba3dc81660ba733b99e1859537`이다.
>
> 이 문서는 M7 Architecture SSOT다. **M6 현재 보장**은 해당 revision의 merged source에서
> 확인한 동작, **M7 요구**는 후속 owner가 구현·검증할 계약을 뜻한다. #151의 source/test 검토와
> #152의 실행 검증은 구분하며, 후자는 위 실험 기록을 따른다. 후속 변경 시 실제 merged source와 decision evidence에 맞춰 갱신한다.

## 1. 기준과 변경 경계

### #153 구현 profile

`ai.runtime.AgentRuntime`은 server-side composition용 process-local component다. Public Run/approval
endpoint나 MCP approval tool은 없다. 지원 operation은 start/result/cancel, 한 provider step,
허용 exact read, original Actor review/approve, 최초 HOLD/explicit same-intent retry, known target
reconciliation과 answer delivery failure observation이다. 모두 current MCP Actor를 검증하고,
approval/retry는 current original credential도 검증한다. 같은 exact delegation의 live Run re-entry만
지원하며 renewal/replacement는 거부한다. 누적 예산과 absolute deadline은 Run 안에 유지된다.

Router/provider 결정은 ADR-0010 그대로다. Trusted local context assembly 후 disclosure/remaining
budget snapshot을 route하고, provider 앞에서 principal/delegation/Tenant/model admission과 bounded
worker를 적용한다. Plan은 서버가 정한 exact HOLD/read material이며 모델은 approval/key/scope를
공급하지 못한다. 기본 상한과 supported 최대치는 ADR-0011과 `RuntimeContract.Limits`를 따른다.
Confirmation wait에는 permit이 없고 unknown usage/cost는 새 provider 실행을 막는다.

MCP의 optional completion hook으로 실제 handler 종료까지 Runtime accounting을 유지한다.
Outer timeout/response loss는 mutation unknown이며 late validated result는 기존 observation만
보완한다. Cancellation 뒤 새 dispatch는 없고 이미 admitted된 작업의 rollback을 추론하지 않는다.
Structured mutation result, 현재 exact GET result와 answer를 분리해 보존한다. Result 반환에도
current authority를 적용한다. Raw context/credential/Product key를 production trace에 기록하지 않는다.

Deterministic fake-provider regression과 real MySQL/TLS Product fixture를 사용한다. Fresh actual-provider
세 flow나 non-synthetic disclosure, Ops tool, restart continuation을 완료했다고 주장하지 않는다.
그 owner는 각각 #155, #154, #156이다. 아래 #151 요구 표는 전체 계약으로 유지하며 #153의
구체 구현·제한은 이 절과 ADR-0011을 함께 따른다.

[Roadmap M7/M8](../roadmap.md#m7-model-router--agent-runtime),
[M6 계약](m6-access-knowledge.md), [Accepted ADR-0009](../adr/0009-m6-authenticated-access-and-knowledge.md),
[Repository Layout](repository-layout.md)을 보존한다. M6 문서의 `현재`/gate는 #130 설계 snapshot이며,
현재 구현·종료 근거는 [M6 closure](../experiments/m6-closure/README.md)와 아래 source를 따른다.
#147의 response-completion grace와 #150의 docs-only verification scope 보정도 기준 revision에 포함된다.

| 경계 | M6 현재 보장 / 근거 | M7 요구 / owner |
| --- | --- | --- |
| Actor/Tenant/Venue | [ActorAccessService][actor-access]의 original/delegation current validation, 좁은 Product credential과 [AuthorizationUseCase][authorization]의 Product permission | Run control, 매 provider disclosure와 다음 work에도 current authority 적용 (#153) |
| Product | [ProductTools][product-tools] → [ProductHttpBinding][product-binding] → [ProductHttpClient][product-client] → 기존 authenticated Product HTTP. Transaction, capacity, idempotency는 Product authority | 모델/Runtime에 business state machine, permission, reliability ledger 복제 금지 (#153/#154) |
| Confirmation | [HoldApprovals][hold-approvals]의 controlled original Actor API와 immutable intent/confirmation | 실제 original Actor handoff, Run/re-entry/fallback safety 강제 (#153/#155) |
| Knowledge | [KnowledgeSearch][knowledge-search]의 pre-query scope와 source별 final current metadata 재검증, untrusted excerpt | 생성 모델 전송에도 current scope와 provider별 disclosure eligibility 적용 (#152/#153) |
| 실행 자원 | [McpEngine][mcp-engine], [LocalAdmission][local-admission]의 한 live instance 보호와 실제 work 종료까지 permit 유지 | MCP 호출 전 provider-only work를 포함한 유한 Run admission/budget (#152/#153) |
| Ops | [기존 observation API][ops-controller]/[read service][ops-read]는 Owner/Manager만 허용. 현재 production registry는 Product 세 tool + `knowledge.search` | 새 `management.event-observations.list`의 Auth/list/call/narrowed Product/final authorization 연결은 #154 |

현재 M6 구현과 Accepted ADR, #151~#157 사이에서 이 공통 계약을 막는 충돌은 확인하지 않았다.
M6의 durable HOLD approval record와 M7의 process-local Run은 서로 다른 ownership이다.
이를 durable Agent Runtime이 이미 존재한다는 근거로 사용하지 않는다.

기존 regression source도 대조했다. [ProductToolsIntegrationTests][product-tests]의 exact approval/
concurrent replay/confirmation expiry/COMMIT response loss/known Location cases,
[M6IntegrationTests][m6-tests]의 current grant/revoke/unknown reconciliation/malicious content cases,
[McpEngineConcurrencyTests][mcp-concurrency-tests]의 timeout/interrupt 뒤 capacity 유지가 M6 근거다.
#151 문서 확정 당시에는 이 test를 재실행하거나 M7 Run/provider regression이 이미 존재한다고 주장하지 않았다.

## 2. Representative workload와 disclosure matrix

모든 scope는 인증된 Actor와 서버가 검증한 delegation에서 얻는다. 아래 입력의 Slot/date/query는
업무 데이터이며 Tenant/role/principal/credential/base URL이나 승인 authority를 입력으로 받지 않는다.
모델이 exact target/material을 확정할 근거가 없으면 clarification 또는 근거 부족으로 종료한다.

| Workload | Input | Actor / authority | Tool과 현재 상태 | Output | Provider에 허용할 수 있는 최소 data class |
| --- | --- | --- | --- | --- | --- |
| Customer | 이미 선택한 exact Slot + positive party size, 또는 authoritative known Reservation reference. 필요한 Venue 안내 질문 | Original Customer의 해당 Venue Customer delegation. HOLD는 original Actor의 exact approval, GET은 자신의 Reservation | 현재 `reservation.hold`, `reservation.get`, `knowledge.search` | Structured HOLD 성공/거부/unknown, known target의 현재 GET 결과, source/version이 붙은 Venue 안내. HOLD 성공을 CONFIRMED로 표시하지 않음 | 승인된 profile의 exact 업무 material, 필요한 own Reservation 사실, `VENUE_PUBLIC`의 bounded excerpt/provenance. Raw Product key/credential/approval authority 제외 |
| Owner·Manager | Granted Venue의 유한 날짜 범위/status, 필요한 운영 안내 질문 | Current Owner 또는 assigned Manager의 management delegation. 조회만 수행 | 현재 `management.reservations.list`, `knowledge.search` | Product의 effective reservation fact와 knowledge guidance를 분리한 제한된 요약 | 허용된 Venue의 최소 reservation facts와 `VENUE_PUBLIC`/`VENUE_OPERATOR` source. 불필요한 customer reference/PII 제거; operator 자료의 외부 공개 승인을 별도로 확인 |
| Ops | Granted Venue의 `from`, `to`, optional `limit`, 제한된 운영 상태 질문 | Current Owner 또는 assigned Manager. Customer/Staff/unassigned Manager/M5 recovery/monitoring credential 제외 | #154가 연결할 `management.event-observations.list` | Event occurred time, projection/intake time, delivery, receipt, null/missing/delay/truncation을 구분. Recovery 필요 가능성과 사람이 확인할 근거만 설명 | 해당 Venue의 필요한 projected event/delivery/receipt facts. Global log/telemetry, recovery credential, internal ledger dump 제외 |

**M6 현재 보장:** Management 예약 tool은 Venue-local 단일 `date`와 optional `status`를 받으며
Staff도 current grant 안에서 읽을 수 있다. #155의 Owner/Manager workload는 이 기존 Staff 권한을
축소하지 않는다. 날짜 범위는 기존 API를 바꾸지 않고 제한된 일별 호출로 처리하거나 입력 범위를
줄인다. Context/result bound를 넘는 목록을 완전한 요약처럼 표시하지 않는다.

**M7 요구:** 위 data class는 provider 전송의 자동 허가가 아니다. Actor의 읽기 권한,
delegation scope, workload의 최소 필요, 외부 공개 승인과 provider/account/API mode의 실제
data-control 조건을 모두 만족해야 한다. Primary/fallback마다 다시 판정한다. 허용 여부가
확인되지 않은 data는 보내지 않는다. Synthetic/demo evidence에도 같은 경계를 적용한다.

Knowledge `evidence`, `no_answer`, `insufficient`, `unavailable`을 구분하고 source/document/version/
visibility를 유지한다. `unavailable`을 빈 결과로 바꾸지 않는다. RAG/tool 자연어는 untrusted data다.
예약 상태·capacity·적용 policy는 Product 결과가 authority이며 안내문으로 덮어쓰지 않는다.
M6의 final metadata observation 뒤 변경을 이미 전송한 내용에서 소급 제거하거나 multi-source
atomic snapshot을 제공한다고 주장하지 않는다. 새 provider 전송 시에는 현재 eligibility를 재검증한다.

Ops는 기존 `projected_at`의 `[from, to)` window, 최대 31일/100 item과 `truncated` 의미를 유지한다.
늦게 projection된 과거 event를 해당 시간에 발생한 모든 event로 해석하지 않는다. Null/empty,
absent receipt, pending, delayed projection, `truncated=true`는 healthy/complete 증거가 아니다.
Recovery/replay/cutover/offset 변경은 이 workload의 tool이 아니다.

## 3. Router와 Runtime의 authority

| 책임 | 결정할 수 있는 것 | 결정할 수 없는 것 / enforcement owner |
| --- | --- | --- |
| Router (#152) | Trusted input classification에 대한 eligible candidate, hard-gate exclusion, versioned deterministic score/tie-break/selection과 근거 | Authentication, Tenant/Venue, Product permission, confirmation, idempotency identity, Product result, recovery authority |
| Provider adapter (#152) | Model request, normalized structured proposal/answer/failure, usage/cost/attempt latency | MCP/Product 직접 실행, 승인 발급, scope 확대, provider-hosted autonomous tool loop |
| Runtime (#153) | Current Actor와 origin, Run state, disclosure, budget/deadline, approval, 기존 effect observation을 검증한 다음 work의 실행 허용/거부 | Product transaction/정책/결과 재결정, 모델 제안으로 authority 생성, 자동 mutation replay |
| M6/Auth/Product/Knowledge | 기존 current delegation/registry/schema/admission, exact Product authorization/transaction, approval binding, corpus publication authority | Router score나 모델의 자연어를 authorization으로 채택 |

Security/disclosure/capability/최소 quality는 scoring으로 상쇄할 수 없는 eligibility hard gate다.
No candidate면 안전하게 unavailable/근거 부족으로 종료하고, one candidate와 tie-break도 재현 가능하게
정의한다. 모델 출력은 항상 실행 제안이다. Runtime validation 이후에도 실제 invocation은 기존 M6
registry, strict JSON schema, delegated Actor, admission과 최종 Product authorization을 통과한다.

Routing record는 workload/profile revision, trusted classification, candidate/measurement/price snapshot,
excluded reason, policy revision, score components/tie-break, selected model, remaining budget/deadline을
연결한다. 재현성은 동일 snapshot에서 exclusion/selection을 재계산하는 의미다. 실제 provider/model/
resolved version/capability와 prompt/schema/request-config/adapter-parser/source/environment manifest,
비교 수치 및 policy weights는 #152 evidence로 정한다. 여기서 특정 vendor나 수치를 사실로 고정하지 않는다.

## 4. Immutable Run origin과 control authority

**M7 요구 (#153):** Run은 original principal reference, originating delegation reference,
trusted Tenant/Venue, workload/profile revision, server-generated Run ID, admission 시각과 absolute
deadline 기준을 immutable origin으로 가진다. Step/provider attempt/proposal/tool invocation/approval/
known Product target reference는 각각 구분한다. Run/step ID와 provider conversation/tool-call ID는
correlation일 뿐 credential, confirmation 또는 Product idempotency identity가 아니다.

M6는 request context/session의 authority를 제공하며 M7 Run/control API를 이미 제공하지 않는다.
아래는 Runtime이 **실제로 지원하는 operation에 적용할 matrix**다. Cancellation/re-entry/result
retrieval을 위해 별도 public HTTP management surface를 만들 필요는 없으며 구체적 adapter와 지원
operation 목록은 #153/#155에서 필요한 최소 범위로 확정한다.

| Operation | Current authority와 origin 호환성 | 추가 gate / 거부 |
| --- | --- | --- |
| Start | Auth가 인증한 current Actor/delegation, server-derived Tenant/Venue와 workload profile/tool ceiling | Run/provider admission, 유한 budget/deadline. Caller origin claim/다른 Actor/scope 거부 |
| Cancellation 요청 (지원 시) | Current authenticated original principal이 Run origin과 같고 해당 Tenant/Venue/profile의 control 권한이 현재 허용됨. Delegated 경로라면 current delegation도 검증 | Run ID만 아는 caller, 다른 Actor/Tenant/Venue, expired/revoked/incompatible authority 거부. 요청은 rollback 증거가 아님 |
| Result retrieval (지원 시) | 같은 original principal과 origin scope 안의 **현재 read/disclosure 권한** | 과거 grant/완료 사실만으로 결과 공개 금지. Current scope 축소 시 해당 data release 거부 |
| Same-process re-entry/resume (지원 시) | 같은 original principal, origin Tenant/Venue/workload와 compatible current delegation. Operation에 필요한 current actions/tools와 disclosure를 재검증 | 기존 cumulative budget/absolute expiry 유지. Origin을 덮어쓰거나 expired/revoked delegation 자체를 live로 복원하지 않음 |
| Replacement/renewed delegation으로 re-entry (지원 시) | 위 호환성을 명시적으로 검증한 경우에만 허용. 새 current delegation reference는 origin reference와 별도 취급 | 기존 scope/approval/disclosure/retry/budget 자동 승계 금지. 이전 HOLD intent/confirmation은 exact delegation 불일치로 사용할 수 없음 |
| HOLD review/approval handoff | `HoldApprovals`의 별도 controlled original Actor authentication과 exact stored review | Run control 권한이나 delegated credential만으로 approve 금지. §8의 M6 binding 유지 |

새 role/grant가 생겨도 origin scope를 확대하지 않는다. 지원되지 않는 re-entry는 안전하게 거부한다.
Authority store unavailable은 fail closed이며 과거 cache/session/model claim으로 새 실행이나 disclosure를
허용하지 않는다. Current 판정 이후 revoke된 이미 admitted transaction을 소급 rollback하지 않는다.
권한 없는 control 요청을 거부하는 것과 내부 deadline 종료/permit cleanup은 별개다.

## 5. Provider admission, budget과 endpoint/disclosure

**M6 현재 보장:** `LocalAdmission`은 principal/delegation/Tenant/registered tool/resource별 finite
rate/concurrency를 제한한다. 이것만으로 MCP 호출 전 생성 provider-only 비용을 통제한다고 주장하지 않는다.

**M7 요구 (#152/#153):** 첫 provider 호출 이전부터 유한 Run admission을 적용한다.

| 통제 대상 | 계약 |
| --- | --- |
| Aggregate admission | Principal, trusted Tenant, provider/model resource별 rate/attempt/concurrency. 새 Run/새 delegation/model 변경으로 aggregate를 우회하지 않음 |
| Live Run budget | 유한 step/provider attempt/tool attempt/input-output token/cost/concurrency와 absolute deadline. Retry/fallback/malformed response/rejected proposal도 해당 budget에 귀속 |
| Dispatch 예약/결산 | Server-controlled 한도 안에서 다음 attempt의 context/output/비용 상한과 남은 budget을 확인. Usage/cost는 `measured`, `estimated`, `unavailable`을 구분하며 timeout/missing usage를 0으로 환급하지 않음 |
| Deadline | Run의 남은 시간과 각 provider/tool boundary의 더 짧은 deadline 적용. `Retry-After`가 남은 deadline을 넘으면 대기/dispatch하지 않음 |
| Saturation / continuing work | Bounded 자원 포화는 거부. 무제한 queue/worker 생성 금지. Timeout/cancel 뒤에도 실제 local work 종료까지 concurrency accounting 유지 |
| Confirmation wait | Provider/tool permit을 점유하거나 attempt/token/cost를 소비하지 않음. Run absolute expiry는 연장하지 않음; 대기 Run metadata 수도 bounded |
| Re-entry / restart | 같은 live Run의 누적 budget은 새 request로 reset 불가. Process-local counter의 restart continuity/global quota는 보장하지 않음 (§10) |

한도와 reservation/accounting mechanism은 후속 구현 선택이며 반드시 positive finite config와
cardinality/metadata 상한, limiter 실패 시 새 admission 거부를 검증한다. 기존 `LocalAdmission`
클래스 재사용 자체는 요구가 아니다. SlotQ security/admission/budget/confirmation 거부는 model/provider
변경으로 우회할 수 없다. Provider rate limit과 SlotQ admission rejection을 같은 fallback 사유로 합치지 않는다.

Endpoint/API mode/model allowlist/credential은 server-controlled configuration만 사용한다. Caller/model/
RAG/tool result가 base URL, credential, unrestricted API mode나 arbitrary redirect destination을
제공·변경할 수 없다. Automatic redirect/SDK retry는 명시적 adapter policy로 통제하고 destination,
credential 전달, 실제 attempt/budget/failure accounting을 검증한다. 관측·통제 불가능한 hidden retry
mode는 지원하지 않는다. Workload에 불필요한 hosted agent loop/shell/web/remote MCP/arbitrary tool
execution은 비활성화한다. Adapter가 tool 실행 authority를 받지 않는다.

Primary와 same-provider alternate model, cross-provider fallback 각각 actual endpoint/account/API mode의
disclosure eligibility를 확인한다. Provider A에 보낸 data가 B에도 허용되는 것은 아니다. Revoked/expired
scope의 data를 새 attempt에 재전송하지 않는다. 최소 context만 전송하고 불필요한 identifiers/PII를 제거한다.
Provider가 data-control을 제공하면 실제 환경의 effective retention/training/region 또는 동등 control을
evidence와 함께 #152에 기록한다. 확인 불가 field는 `unavailable`로 남기며 필요한 disclosure 조건을
입증하지 못한 후보는 eligible로 취급하지 않는다. Account identifier/secret config 자체는 evidence에서 제외한다.

Provider secret, Product credential, original/MCP credential, raw idempotency key는 model context,
production trace/audit, raw evidence, exception output, Issue/PR에 남기지 않는다. Raw prompt/query/document/
excerpt와 uncontrolled provider/SQL exception은 production telemetry에 복사하지 않는다. Opaque references,
safe normalized categories, bounded usage/cost/latency metadata로 관측한다. Server가 실제 MCP HOLD schema의
key/confirmation fields를 결합하며 모델에게 secret을 제공해 채우게 하지 않는다.

## 6. Execution, invocation, Product effect와 실제 MCP wire

**M7 요구 (#153):** 아래 세 축을 한 enum이나 success/failure Boolean으로 합치지 않는다.

| 축 | 상태/관측 의미 |
| --- | --- |
| Run execution | Admission → routing → provider call → waiting confirmation/tool processing; cancellation requested, deadline/budget termination, terminal. 내부 실행 제어 상태이며 Product effect 증거가 아님 |
| Tool invocation observation | `not_dispatched`: 해당 attempt의 Product/tool effect dispatch 이전 확정 거부. `response_observed`: authoritative structured tool response 관측. `invocation_unknown`: dispatch/result를 확정할 수 없음 |
| Product effect observation | `not_applicable`, `succeeded`, `rejected`, `outcome_unknown`. Read는 mutation effect가 없지만 read 결과의 관측 불명은 별도로 보존 |

**M6 현재 wire:** `McpEngine.call`은 JSON-RPC `result` 안 `CallToolResult.structuredContent`, text,
`isError`, optional `_meta`를 반환한다. Structured Product result는 `ProductTools`가 만들며
`outcome=succeeded/rejected/outcome_unknown/not_dispatched`를 사용한다. `isError=true`만으로
확정 Product 거부를 판정할 수 없다. `_meta`의 request/intent/confirmation/knownTarget/Product request
references는 correlation/reconciliation 자료이며 credential이나 성공의 대체 증거가 아니다.

| 실제 관측 / boundary | Invocation observation | Mutation Product effect | Runtime disposition |
| --- | --- | --- | --- |
| Runtime에서 아직 미전송이 확정되거나 pinned MCP schema/auth/admission/protocol 단계의 pre-handler 거부가 확인됨 | `not_dispatched` | `not_applicable` | Safe reason 유지. 이 attempt에 새 effect가 없다는 뜻 |
| Engine common `structuredContent.outcome=not_dispatched`, 또는 HOLD approval/material/binding 준비의 확정 거부 | `not_dispatched` | `not_applicable` | Handler 진입과 실제 Product dispatch를 구분. Confirmation/security 거부를 fallback으로 우회하지 않음 |
| Valid Product tool `outcome=succeeded`와 DTO 관측 | `response_observed` | `succeeded` | Product result와 authoritative target/effective state 보존 |
| Valid Product tool `outcome=rejected`와 safe Product problem 관측 | `response_observed` | `rejected` | 해당 attempt의 authoritative rejection. Parameter/model 변경으로 자동 재실행 금지 |
| Valid Product tool `outcome=outcome_unknown` 관측 | `response_observed` | `outcome_unknown` | Product response loss/5xx/redirect/unresolved response 등을 rollback으로 해석하지 않음 |
| Product handler 시작 뒤 Engine timeout/interruption/exception, output schema/size failure 등의 common `outcome=unknown` | `invocation_unknown` | `outcome_unknown` | Handler가 이미 COMMIT했을 수 있음. `not_dispatched`/definite failure로 축소 금지 |
| [McpServlet][mcp-servlet] async fail-safe HTTP 503의 `{category: timeout, outcome: unknown}` | `invocation_unknown` | `outcome_unknown` | JSON-RPC 정상 envelope 밖 응답도 처리. Outer timeout으로 dispatch 미실행을 추론하지 않음 |
| `tools/call` 응답 유실·해석 불가·불완전, dispatch 여부를 증명하지 못하는 HTTP/JSON-RPC error | `invocation_unknown` | `outcome_unknown` | Status/error code/transport exception 하나로 pre-dispatch라 판정하지 않음 |
| Cancel/timeout/disconnect 뒤 이미 dispatched mutation의 late result를 관측하지 못함 | `invocation_unknown` | `outcome_unknown` | Actual work/COMMIT 가능성 유지. Late authoritative result를 얻으면 관측 보완 |
| Read/knowledge timeout, generic unknown 또는 transport loss | Read invocation unknown; valid `unavailable` 응답이면 response observed | `not_applicable` | 빈 결과/`no_answer`/확정 실패로 바꾸지 않음. Current authority/disclosure/budget에서 safe read retry만 별도 판정 |

Retrieval의 Engine handler failure는 현재 tool별 `failureOutcome=UNAVAILABLE`에 따라 common
`outcome=unavailable`일 수 있고 `KnowledgeSearch` 정상 envelope는 `category/reason/results`를 쓴다.
Product의 `outcome` enum을 모든 tool에 일괄 적용하지 않는다. Servlet의 최대 1초 completion grace는
MCP 최대 30초 work deadline을 늘리거나 새 work admission을 허용하지 않는다.

관측은 invocation attempt와 immutable intent에 귀속한다. 후속 attempt의 `not_dispatched`/`rejected`는
이전 mutation attempt의 성공/unknown을 지우지 않는다. Runtime terminal/cancelled도 effect observation을
삭제하지 않는다. Known target은 실제 Product DTO/검증된 Location/M6 stored authoritative reference에서
얻은 경우만 인정한다. 모델의 UUID 추측, provider tool-call ID나 audit missing outcome은 증거가 아니다.

## 7. Provider failure disposition

다음은 #152 adapter/Router와 #153 Runtime이 구현할 normalized policy다. Same-provider retry,
same-provider 다른 model과 cross-provider fallback을 구분한다. 허용 가능 표시는 무조건 자동 retry가
아니며 current authority/disclosure/remaining budget/deadline과 사전에 정한 bounded policy를 모두 요구한다.
실제 지원 경로와 unsupported/terminal 경로는 #152 evidence로 확정하고 #155/#157에서 검증한다.

| Failure | Same-provider retry | Alternate model / cross-provider | Terminal / safe 사용자 표면 |
| --- | --- | --- | --- |
| Temporary overload / rate limit | Bounded backoff/`Retry-After`가 deadline 안이면 허용 가능. Attempt/usage accounting 유지 | Eligible alternate만 허용 가능. Provider-wide failure는 같은 provider model 변경이 해결한다고 가정하지 않음 | Budget/deadline 소진 또는 지원 경로 없음은 unavailable |
| Authentication / configuration / account quota unavailable | 같은 invalid credential/config의 자동 반복 금지 | Same-provider model은 실제 model-specific 문제와 정상 account 접근이 입증된 경우만. Cross-provider는 별도 접근/disclosure가 확인된 지원 경로만 | Safe unavailable/config category. Raw account/credential/provider error 비공개 |
| Provider policy refusal | 같은 거부 요청을 자동 반복하거나 표현만 바꿔 우회하지 않음 | 공통 safety/security/disclosure 거부는 terminal. Provider-specific capability 제한으로 분류·입증하고 다른 eligible mode가 허용되는 정책에서만 alternate 가능 | Safe refusal/제한 설명. SlotQ 금지를 provider 변경으로 해제하지 않음 |
| Timeout / remote completion unknown | Local work 종료/계속 실행을 accounting하고 remote completion/usage unknown 유지. 새로운 provider-only attempt는 bounded policy에서만 | Disclosure 재검증한 eligible alternate, 남은 budget이 있을 때만. 미확인 비용을 0으로 보아 fallback하지 않음 | Unavailable/timeout와 비용 관측 limitation. Mutation replay authority 없음 |
| Malformed / invalid structured response | Invalid proposal은 dispatch 금지. 제한된 provider regeneration/parser policy 안에서만 retry 가능 | Eligible alternate로 bounded 재생성 가능; rejected proposal도 budget 소비 | Invalid output/unavailable. 구조를 추측해 forbidden tool/material을 보정·실행하지 않음 |
| Ordinary model-quality failure | #152의 고정 oracle/quality floor에 따른 제한된 재생성 가능 | Hard gate를 만족하는 alternate만. Product fact/unknown/source를 왜곡하는 answer는 전달하지 않음 | Clarification/근거 부족/partial 또는 안전한 실패 표면. 이미 관측한 Product 결과 유지 |
| SlotQ security/admission/budget/confirmation rejection | Provider retry로 해결하지 않음 | Fallback 금지 | 해당 safe rejection 또는 Run termination. 원 Actor의 별도 허용 절차를 자동 생성하지 않음 |

Provider failure와 MCP/Product outcome은 별개다. Provider-only request 재시도는 Product mutation retry가
아니다. Tool success 뒤 answer-only fallback에서는 기존 structured result만 사용하고 mutation dispatch를
다시 열지 않는다. Read retry도 현재 권한·scope와 남은 tool/provider budget을 소비한다.

## 8. Approval, delegation replacement와 mutation safety

**M6 현재 보장:** `HoldApprovals.prepare/approve/review`는 MCP registry/HTTP tool 밖의 controlled
server-side API다. `prepare`는 current original Actor와 exact delegation을 검증하고 exact Venue/Slot/
party size와 서버 생성 key의 immutable review를 저장한다. `approve`는 original Actor를 새로 인증하고
stored approver/current delegation과 window를 검증한다. `review`는 authenticated original approver의
stored review 조회이며 그 자체가 current delegation 승인이나 dispatch 허가는 아니다.

`dispatch`는 original principal/exact delegation/Tenant/Venue/tool/action/intent/confirmation/Slot/party/key를
검증하고 row lock 아래 최초 dispatch 시각을 고정한다. 모델의 Boolean/`confirmed=true`, provider response,
RAG/MCP audit, MCP approval tool이나 모델 생성 approval reference는 승인 근거가 아니다.

| 경계 | 보존할 M6 계약 / M7 enforcement |
| --- | --- |
| Original approval | Exact immutable review를 같은 original Actor에게 표시하고 controlled adapter로 approve. 모델의 요약을 stored review 대신 승인받지 않음 |
| Material binding | Target/party/key/action/Actor/delegation/intent 변경 시 기존 confirmation 거부. 새 material은 새 intent/approval 필요 |
| Confirmation | 최대 5분이며 delegation expiry를 넘지 않음. 첫 dispatch 전에는 prepared expiry, 이후에는 최초 dispatch + 15분 retry 종료가 추가 상한. Server clock, 만료 경계부터 dispatch 거부 |
| Retry horizon | 최초 dispatch + 최대 15분. Current delegation과 valid confirmation, same immutable intent/same key의 **명시적 retry만** 허용. 새 confirmation도 최초 시각/key를 바꾸지 못함 |
| Product idempotency | [ADR-0005](../adr/0005-use-mysql-hold-idempotency-record.md)의 `(Tenant, authenticated Customer, opaque key)`와 semantic fingerprint, 최초 성공 `completedAt + 24h` 보존. Run/provider ID로 key 생성·대체 금지 |
| Replacement delegation | Compatible read-only re-entry가 지원돼도 old intent/approval을 새 delegation으로 이전하지 않음. Mutation이 필요하면 새 delegation에서 새 M6 intent/approval 절차 |
| Expired / 확인 불가 intent reference | 새 dispatch fail closed. Known target만 current read 권한으로 reconcile. Clock regression/record loss/확인 불가 first dispatch를 안전한 retry로 재구성하지 않음. Valid intent의 Product outcome unknown은 위 explicit retry 계약을 따름 |

**M7 요구 (#153/#155):** Provider retry/fallback, duplicate proposal, 새 Run/re-entry는 자동 HOLD 재실행이나
기존 approval 재사용의 근거가 아니다. 최초 승인된 dispatch 이후에는 explicit same-intent retry 이외의
mutation replay를 허용하지 않는다.
Fallback 뒤 동일 proposal을 받거나 COMMIT 뒤 응답을 잃어도 자동 새 key/새 intent로 반복하지 않는다.
Replacement delegation에서 새 intent가 필요하다는 사실도 기존 unknown을 실패로 만들지 않는다.
사용자가 unknown과 duplicate 가능성을 인지한 별도 명시적 새 effect를 원할 때만 fresh intent/key를 승인한다.

M6 durable intent/binding은 Product reliability ledger와 별개이며 Runtime이 두 table을 직접 조회해 효과를
추론하지 않는다. 기존 retention/tombstone 계약을 Run expiry나 provider budget으로 연장하지 않는다.
Public approval endpoint/MCP approval tool/provider-controlled approval은 추가하지 않는다. 필요한 client/UI/
adapter는 기존 controlled boundary 안에서 선택한다. 새 remotely reachable approval surface 요구는
구현 전 별도 security/architecture decision이 필요하며 #151의 허용 scope가 아니다.

## 9. Cancellation, late completion, reconciliation과 answer

**M7 요구 (#153):** Cancellation이 수락되거나 Run deadline/budget이 끝나면 새 provider/tool dispatch를
시작하지 않는다. Cancellation/dispatch race는 Runtime의 admission 순서로 구분하며 이미 admitted
request를 미전송으로 소급 표시하지 않는다. Thread interruption, client disconnect 또는 HTTP timeout은
local worker/remote provider/Product transaction의 물리적 종료나 rollback을 증명하지 않는다.

M6는 실제 runner/Product request exit까지 permit을 유지한다. M7도 continuing local provider/tool work를
accounting하고 late result를 기존 attempt/effect에만 반영한다. Late completion으로 새 step/fallback mutation을
시작하지 않는다. Remote completion을 확인 못하면 remote execution/cost는 unknown이며 durable cancellation
acknowledgement를 주장하지 않는다. Late data의 사용자 공개도 current result-read/disclosure 권한을 검증한다.

| 관측 | 복구 / 사용자 결과 |
| --- | --- |
| Unknown + authoritative known Reservation | 기존 `reservation.get`/exact Product GET으로 current ownership/effective state 확인. Initial command 성공 응답과 현재 state observation을 구분 |
| Unknown + target 없음 | Unknown 유지. Target 추측/전체 ledger 검색/자동 fresh-key mutation 금지. 유효 horizon 안에서만 original Actor의 explicit same-intent retry 가능 |
| Exact GET 404/timeout/권한 축소 | 최초 mutation 실패/rollback 증거로 해석하지 않음. Current read unavailable/denied와 unresolved effect를 별도 표시 |
| Product success 뒤 provider/answer/answer delivery 실패 | 이미 관측한 Product success/target을 보존. 가능한 controlled 결과 표면에 structured fact를 별도로 전달; answer failure만 표시 |
| Run terminal 뒤 authoritative late Product result | 이전 observation 보완 가능. 실행 terminal은 유지하며 새 mutation/answer work를 자동 시작하지 않음 |

Product HOLD 성공은 이후 GET에서 expired/cancelled 등 현재 상태를 관측해도 기존 성공이 취소된 것이 아니다.
`outcome_unknown`을 자연어로 success/failure라고 단정하지 않는다. Best-effort MCP audit failure나 answer
failure는 Product effect를 바꾸거나 mutation retry를 유발할 수 없다.

## 10. HTTP / process-local / restart-durable requirement와 gate

#156 판정 전 M7 기본 지원은 **bounded process-local Run**이다. HTTP response lifetime과 live process의
work lifetime, restart continuation을 구분한다. M6의 MCP 최대 30초/Product connect 최대 2초/response 최대
15초/retrieval 최대 10초와 최대 60초 Product command admission gate는 개별 기존 boundary다.
이를 M7 전체 Run duration이나 새 생성 provider timeout으로 복사하지 않는다. 유한 Run 한도는 #152/#153이
정하고 실제 flow duration/confirmation evidence로 #156에서 재검토한다.

| Requirement | HTTP-bounded | Process-local continuation | Restart-durable / 판정 owner |
| --- | --- | --- | --- |
| Provider → tool → answer | 유한 deadline 안 success/reject/unavailable/unknown 반환 | HTTP 이후 남은 local work 종료/late observation도 accounting. 무제한 agent loop 없음 | Restart 후 resume/자동 dispatch는 현재 보장 없음. 실제 필요는 #156 |
| Confirmation wait / re-entry | HTTP를 끝내고 wait state/expiry를 안전하게 표시 가능 | Permit 없이 bounded wait, 지원되는 explicit re-entry에서 current authority와 기존 budget/expiry 검증 (#153/#155) | M6 approval record가 남아도 Run continuation/budget 복구가 아님. Restart 후 confirmation continuation 요구는 #156 |
| Cancellation | 새 dispatch 중단과 요청 결과를 effect와 구분 | Actual worker exit/late result까지 accounting | Durable cancellation/remote stop acknowledgement 요구는 #156; 현재 지원 주장 없음 |
| Saturation | Finite admission rejection | Bounded in-flight/metadata, 숨은 backlog 금지 | Persistent queue/backlog/independent executor/delayed retry 필요성은 #156; synthetic overload만으로 채택하지 않음 |
| Process termination 전/후 dispatch | Pre-dispatch가 입증되면 no effect; dispatch 가능성이 있으면 unknown | Process 종료 시 local Run/permit/observation 유실 가능 | Product COMMIT/idempotency와 M6 approval durability는 각 owner에 남음. Restart 후 Run resume/budget continuity/자동 mutation 보장 없음 |
| Restart 뒤 사용자 복구 | Current credential/delegation로 새로 인증; known target의 explicit exact GET | 이전 Run을 임의 재구성하거나 누적 budget을 복구했다고 주장하지 않음 | Unknown target은 unknown 유지. 새 intent는 §8; durable continuity requirement면 별도 gate |

**#156 decision gate의 질문과 evidence:** 실제 두 모델/세 flow의 duration 분포와 deadline 초과 비율,
confirmation wait/re-entry와 resource 점유, provider-only/tool saturation, cancellation 전후와 continuing
worker, process termination before/after dispatch, COMMIT+response loss, restart 뒤 자동 mutation 부재 및
explicit re-entry/reconciliation을 #152/#153/#155 evidence로 검토한다. 사용자가 restart 뒤 같은 Run을
이어야 하는지, 자동 dispatch/durable cancel/HTTP 밖 executor/persistent backlog/delayed retry가 실제
requirement인지 확인한다. 긴 latency, durable approval record, 작은 traffic 또는 Kafka의 존재만으로 결론내리지 않는다.

| #156 결론 | 남길 decision / 다음 단계 |
| --- | --- |
| Durable 필요 없음 | Supported lifetime, HTTP 종료/wait/re-entry/restart/cancel/unknown limitation과 user/operator 복구 경로, 기각 대안을 SSOT에 기록. 추가 persistence/workflow framework 없음 |
| Durable 필요 있음 | Process-local로 충족 못하는 실제 requirement/evidence, 보존할 security/correctness, #153/#155 영향, ADR 영향 후보와 기각 대안을 기록. 별도 Issue 설계 → conditional M7-D 범위/완료조건 → 구현/검증/merge → #157 |

M7-D는 #156 evidence 전에 생성하거나 #156 안에 숨겨 구현하지 않는다. Positive decision이면 M7-D 완료
전 M7 closure 불가다. Durable record는 retry authority가 아니며 resume에도 current auth/confirmation/
retry horizon을 재검증해야 한다. Product business state/idempotency ledger 복제, Kafka offset/M5 delivery/
recovery state를 Agent execution authority로 사용, 근거 없는 Redis/Kafka/Kubernetes/service 분리는 금지한다.
Multiple live MCP/shared quota나 Product/MCP topology 변경은 ADR-0009 재검토 대상이다. Provider service
분리와 Product/MCP deployment 분리를 하나의 결정으로 묶지 않는다.

## 11. Module dependency, ownership과 decision history

같은 Backend build의 기존 경계를 유지한다. Product는 AI 없이 동작하며 Product/Auth/Event core가
Router/Runtime/provider/concrete AI integration을 역참조하지 않는다. `mcp` 공통 foundation은 concrete
Product/retrieval handler를 역참조하지 않고 explicit integration composition이 등록한다.
Runtime/Router/adapter의 구체 package/API는 후속 최소 구현 선택이며 빈 module/service를 만들지 않는다.

| Owner boundary | 허용 dependency / 소유 state | 금지 |
| --- | --- | --- |
| Router / provider adapter | Trusted workload/candidate snapshot와 normalized provider contract | Product/MCP 직접 실행, Auth/approval authority 생성, Product DB/JPA 조회 |
| Runtime | Auth public boundary, Router/adapter contract, M6 registry/invocation boundary, controlled approval handoff | Product domain/transaction/locking/ledger 복제, schema/security bypass, 자체 broad credential |
| MCP Product integration | Fixed-origin authenticated Product HTTP, Auth public credential port, 자체 HOLD intent/confirmation | Product application port로 HTTP bypass, Product/reliability/event/recovery table 직접 접근 |
| Knowledge integration | Public corpus/catalog port, current publication/visibility와 untrusted source/version | Product DB/JPA/authority table 직접 접근, index를 publication authority로 사용 |
| Auth / Product / Events | 각자의 credential/delegation, business transaction/idempotency, event delivery/recovery state | AI dependency cycle, authority store/table 공유 |

현재 [McpArchitectureTests][mcp-architecture]와 [KnowledgeArchitectureTests][knowledge-architecture]는
이 M6 dependency 방향을 검증하는 기존 test source다. M7 owner는 확장한 경계의 regression을 소유한다.
Ops tool은 Product observation HTTP만 호출하며 M5 내부 table/recovery/Kafka offset을 직접 다루지 않는다.

| Issue / Milestone | 선행 | Primary ownership / SSOT 갱신 책임 |
| --- | --- | --- |
| [#151](https://github.com/krestar/slotq/issues/151) | M6 Complete + #148/#147, #149/#150 | 공통 workload/authority/admission/failure/architecture와 decision 기준. Production 구현 없음 |
| [#152](https://github.com/krestar/slotq/issues/152) | #151 | 실제 최소 두 생성 모델 비교, eligibility/scoring/replay, provider adapter/failure/disclosure, bounded oracle/manifest/evidence와 Router decision |
| [#153](https://github.com/krestar/slotq/issues/153) | #151 + #152 | Process-local Runtime/Run control, admission/budget, original approval, unknown/retry/cancel/late/answer enforcement와 Runtime decision |
| [#154](https://github.com/krestar/slotq/issues/154) | #151 | Owner/Manager-only Ops observation의 Auth/list/call/exact Product operation/final authorization. Existing Staff reservation read 보존 |
| [#155](https://github.com/krestar/slotq/issues/155) | #152 + #153 + #154 | 동일 production Runtime의 세 representative flow와 fresh actual-model/MCP/Product evidence, #156 duration/wait/restart/cancel 입력 |
| [#156](https://github.com/krestar/slotq/issues/156) | #152 + #153 + #155 | Durable 필요/불필요 판정과 limitations, 필요하면 별도 M7-D 설계 입력. Durable 구현 자체는 아님 |
| [#157](https://github.com/krestar/slotq/issues/157) | #151~#156 + positive gate면 완료된 M7-D | 최신 merged implementation/evidence/SSOT/ADR의 통합 closure. 새 architecture 선택 없음 |
| M8 | M7 Complete | Generic evaluation dataset/threshold history/release gate/전체 AI regression, production load/security/HA·backup-restore qualification |

M7은 선택/실행 검증에 필요한 작은 fixture/oracle/scoring rule/calculation code와 evidence를 소유한다.
#152는 workload별 정상/clarification/근거 부족/forbidden·잘못된 실행 case와 한국어 입력, 모델별 최소 3회,
고정 비용/token/output 상한으로 비교한다. #155는 flow별 fresh actual provider run을 남긴다. 이는 M8의
generic evaluation이나 production quality/SLA/release readiness가 아니다.
Actual provider, deterministic fake, injected failure, fixture-only baseline, skipped/unavailable/unsupported를
분리한다. Credential 부재 skip을 PASS로 세지 않는다. Fixture/manifest/config/schema/recalculation code와
간결한 결과 요약·재현 명령만 장기 추적하고 raw JSON/CSV/model output/log/XML/transient dump는 gitignored
`build/`에 둔다. Historical PASS는 fresh execution에 더하지 않는다.

[ADR register](../adr/README.md)의 Model Router/Agent Runtime은 #151만으로 `Accepted`로 승격하지 않는다.
#152/#153/#156 owner의 실제 decision evidence가 필요하다. Accepted architecture 변경이나 후속 Milestone이
의존할 중요한 새 선택은 ADR 정책에 따라 Proposed/Accepted/Superseded와 근거/재검토 조건을 기록한다.
미확정 durable 설계는 Accepted 구현으로 표시하지 않는다. #157은 이미 primary owner가 결정한 상태와
source/evidence 일치만 확인한다.

## 12. Threat/failure 검토와 #151 완료조건 대조

아래는 문서상 disposition과 후속 executable verification owner다. 이번 documentation-only 변경에서
Backend/Frontend full test·clean build나 actual provider execution을 수행한 결과표가 아니다.

| Threat / failure case | 확정 disposition | 후속 검증 owner |
| --- | --- | --- |
| Cross-Actor / cross-Tenant/Venue Run control | Current Actor + immutable origin 호환성 검증, ID-only 접근 거부 (§4) | #153/#155/#157 |
| Expired/revoked/replacement delegation | Current authority fail closed; compatible re-entry도 old HOLD binding 이전 금지 (§4/§8) | #153/#155 |
| Provider-only 반복 / budget exhaustion | MCP 전 aggregate admission, 모든 live Run attempt accounting; fallback으로 budget reset 금지 (§5) | #152/#153/#156 |
| MCP response loss / Product COMMIT 뒤 unknown | Invocation/effect unknown 유지, known target exact GET만 reconciliation (§6/§9) | #153/#155/#156 |
| Fallback 뒤 duplicate mutation | Provider retry와 mutation retry 분리; 자동 replay/fresh key 금지; 이전 effect 유지 (§7/§8) | #153/#155 |
| Confirmation substitution | Original approver + immutable material/key/intent/exact delegation 검증 (§8) | #153/#155 + M6 regression |
| Cancellation / late Product completion | 새 dispatch 중단, 실제 exit까지 accounting, rollback 추론 금지; late effect만 보완 (§9) | #153/#155/#156 |
| Provider failure / disclosure eligibility | Failure별 terminal/eligible alternate 구분, 매 전송 current scope·실제 provider 조건 재검증 (§5/§7) | #152/#153/#155 |
| Product success 뒤 answer failure | Structured success/target 보존, answer-only fallback은 mutation dispatch 없음 (§9) | #153/#155 |
| Restart / durable requirement | Local Run continuity 보장 없음, 자동 mutation 없음; actual evidence로 gate와 conditional M7-D (§10) | #156, positive면 M7-D, #157 |
| Ops Staff/credential substitution 및 incomplete observation | Owner/Manager-only 각 boundary, existing Staff read 유지, projected/null/truncated 의미 보존 (§2/§11) | #154/#155 |

| #151 완료조건 | 계약 위치 |
| --- | --- |
| 세 representative flow와 authority 범위 | §2 |
| Router/Runtime 결정 권한 분리 | §3 |
| 지원 Run control의 current authority | §4 |
| 불필요한 public management surface 없음 | §4/§8 |
| Provider 호출 전 유한 admission | §5 |
| Generic/transport unknown 포함 outcome mapping | §6 |
| Provider failure별 retry/fallback disposition | §7 |
| M6 delegation/confirmation/unknown/retry horizon 보존 | §4/§6/§8/§9 |
| 새 delegation의 old HOLD intent/approval 승계 금지 | §4/§8 |
| Endpoint/secret/disclosure 경계 | §2/§5/§7 |
| Controlled server-side approval boundary | §8 |
| Durable gate 질문/evidence/owner | §10/§11 |
| Architecture SSOT와 decision ownership | §1/§11 |
| M7 bounded evidence / M8 generic evaluation 구분 | §11 |

#151은 Production Router/Runtime/provider adapter/comparison, 새 Product business tool, public Run/approval
endpoint, generic OAuth/IdP/workflow/evaluation framework, durable persistence/async executor, Kafka/Redis/
Kubernetes 도입이나 M6/M5/Product 재설계를 하지 않는다. Availability 탐색/confirm/cancel/Waitlist,
management/recovery mutation, 큰 chatbot UI도 이번 representative workload의 완료조건이 아니다.

현재 source와 Accepted decision으로 해소 못하는 중요한 보안·권한·실패 충돌이 후속 구현에서 발견되면
해당 primary owner가 근거와 blocker를 기록하고 decision을 해결한다. #157 closure나 provider fallback에서
임시 완화 계약으로 덮지 않는다.

[actor-access]: ../../backend/src/main/java/com/slotq/auth/persistence/ActorAccessService.java
[authorization]: ../../backend/src/main/java/com/slotq/auth/application/AuthorizationUseCase.java
[product-tools]: ../../backend/src/main/java/com/slotq/integration/mcp/product/ProductTools.java
[product-binding]: ../../backend/src/main/java/com/slotq/mcp/ProductHttpBinding.java
[product-client]: ../../backend/src/main/java/com/slotq/integration/mcp/product/ProductHttpClient.java
[hold-approvals]: ../../backend/src/main/java/com/slotq/integration/mcp/product/HoldApprovals.java
[knowledge-search]: ../../backend/src/main/java/com/slotq/integration/mcp/knowledge/KnowledgeSearch.java
[mcp-engine]: ../../backend/src/main/java/com/slotq/mcp/McpEngine.java
[mcp-servlet]: ../../backend/src/main/java/com/slotq/mcp/web/McpServlet.java
[local-admission]: ../../backend/src/main/java/com/slotq/mcp/LocalAdmission.java
[ops-controller]: ../../backend/src/main/java/com/slotq/integration/operations/ManagementEventObservationController.java
[ops-read]: ../../backend/src/main/java/com/slotq/integration/operations/OperationsObservationReadService.java
[mcp-architecture]: ../../backend/src/test/java/com/slotq/architecture/McpArchitectureTests.java
[knowledge-architecture]: ../../backend/src/test/java/com/slotq/architecture/KnowledgeArchitectureTests.java
[product-tests]: ../../backend/src/test/java/com/slotq/mcp/ProductToolsIntegrationTests.java
[m6-tests]: ../../backend/src/test/java/com/slotq/mcp/M6IntegrationTests.java
[mcp-concurrency-tests]: ../../backend/src/test/java/com/slotq/mcp/McpEngineConcurrencyTests.java
