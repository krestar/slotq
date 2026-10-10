# ADR-0011: Exact delegation과 outcome 관측으로 bounded process-local Agent Runtime 구성

- 상태: `Accepted` (controlled process-local profile)
- 결정일: 2026-10-11
- 관련 Issue: [#153](https://github.com/krestar/slotq/issues/153)
- 공통 계약: [M7 Architecture](../architecture/m7-model-router-agent-runtime.md)
- 구현 기준 main: `7c51b17b029f67ba3dc81660ba733b99e1859537`

## 맥락

ADR-0010의 Router/provider는 eligible 후보와 한 provider attempt만 소유한다.
M6는 개별 MCP admission, original Actor approval과 authenticated Product HTTP를 소유한다.
이를 연결할 때 Run ID, 모델 proposal, timeout 또는 answer failure를 실행 권한이나
Product 결과로 잘못 해석하지 않는 공통 실행 경계가 필요하다.

## 결정

`ai.runtime.AgentRuntime`을 controlled server-side composition에서 사용하는 일반 Java
component로 둔다. HTTP/controller/MCP approval tool, 자동 활성화, executor queue, persistence를
추가하지 않는다. Production 구성은 기존 `ActorAccess`, `ToolRegistry`, `McpEngine`,
`HoldApprovals`와 서버가 고정한 Router candidates/context/provider를 주입한다.
실제 adapter는 `GeminiAdapter::generate`로 연결할 수 있다. 현재 actual candidate의
synthetic disclosure와 Management/Ops no-candidate 결정을 유지한다.

Run의 origin은 immutable delegated Actor, trusted scope/workload, server UUID, admittedAt와
absolute deadline이다. 모든 제공 operation은 opaque MCP credential을 현재 Auth 경계에서
검증한다. 같은 exact delegation의 process-local 재진입만 허용한다. Renewal/replacement는
기존 Run 접근·approval·retry를 승계하지 못한다. Compatible replacement read re-entry도
이번 profile에서는 제공하지 않는다. Actor/Auth/Product core는 Runtime을 역참조하지 않는다.

Customer Run은 Customer delegation을, management/ops Run은 현재 Owner 또는 배정된 Manager의
management delegation을 요구한다. Auth의 현재 역할 검증을 시작·제어·전송 경계에서 다시 적용한다.
Staff의 기존 M6 예약 조회 권한은 유지하며, Ops tool의 각 단계 인가는 #154가 소유한다.

Server-owned `Plan`은 exact HOLD material과 허용 read arguments를 고정한다. `generate`는
한 provider step만 수행한다. 모델의 HOLD proposal은 plan과 exact 일치해야 하고, approval은
`reviewHold`가 반환한 key 없는 immutable review를 original Actor가 확인한 뒤 `approveHold`에서
인증·대조한다. 실제 intent/key/confirmation은 private Run state에서 기존 MCP schema에 결합한다.
`dispatchHold`는 최초 시도만, `retryHold`는 original Actor의 별도 explicit same-intent 시도만
허용한다. 동일 Run에서 success/rejection 뒤 mutation replay는 거부한다. M6의 5분 confirmation,
최초 dispatch 기준 15분 horizon과 Product success retention을 변경하지 않는다.

Run/step/provider/tool/token/output/cost/deadline/metadata capacity는 유한하다. 기본 한도는
12 step, provider 3회, tool 6회, token 150000, output 6144, USD 0, lifetime 10분,
attempt wait 30초이며 server plan도 정해진 최대치 안에서만 구성한다. Provider 앞에는 별도의
`LocalAdmission`을 적용하여 principal/delegation/Tenant/model bucket과 전체 worker capacity를
제한한다. 새 Run이나 renewal도 principal/Tenant bucket을 재사용한다. 이 한도는 single-instance
local 보호이며 shared/global quota 또는 production load qualification이 아니다.

`ProviderBudget`은 live Run 전체에서 재사용한다. Unknown usage의 token reservation과 output
reservation은 유지하고, unknown cost는 unavailable로 처리하여 새 provider work를 막는다.
현재 무료 가격은 확인된 usage가 있을 때만 estimated USD 0이다. ADR-0010의 모든 failure terminal,
provider retry/fallback 미지원 정책을 유지한다. Confirmation wait에는 worker/permit이 없으며
absolute deadline은 그대로다. Context assembly는 trusted local/nonblocking code이며 I/O나
tool 호출을 수행하지 않는다. 각 전송에서 current authority와 Router disclosure를 다시 검증한다.

Execution, invocation, Product effect를 별도 축으로 기록한다. MCP common failure의
`not_dispatched`는 확정 거부이고 handler unknown/outer response loss는 mutation unknown이다.
`isError`/HTTP status만으로 Product rejection을 만들지 않는다. Valid structured response만
Product success/rejection/unknown에 반영한다. 후속 not-dispatched는 이전 attempt의 unknown을
지우지 않는다. Structured mutation result와 exact GET의 현재 reconciliation result를 별도로
제공하며 GET 404는 최초 mutation rollback의 증거가 아니다.

MCP의 optional in-process `Completion` hook은 schema 검증된 late result와 실제 runner 종료만
관측한다. 기존 wire/auth/admission/permit 정책은 그대로다. Runtime permit은 outer worker와 MCP
handler가 모두 끝난 뒤 해제한다. Provider permit도 실제 adapter worker exit까지 유지한다.
Cancellation과 dispatch의 순서는 synchronized admission gate에서 결정한다. 이미 시작된 작업의
remote stop/rollback을 추론하지 않고 late result는 기존 observation만 보완한다. Provider late
text로 새 step을 시작하지 않는다. 응답 반환/result-read에서도 current authority를 확인한다.

Run 결과의 safe metadata에는 UUID/step ordinal, 고정 model/tool, execution/effect, budget과
종료 여부를 둔다. Raw prompt, credential, Product key, provider output은 trace/log label에
기록하지 않는다. Structured facts/answer는 controlled authenticated 결과에서만 제공한다.
Answer failure/delivery failure가 이미 관측한 Product success를 변경하지 않는다.

## 대안과 근거

| 대안 | 판단 |
| --- | --- |
| 기존 MCP만 호출하고 Runtime budget 생략 | Provider-only work와 누적 예산/Run authority를 보호하지 못하므로 기각 |
| MCP wire timeout 시 Runtime permit 해제 | 실제 continuing handler가 quota를 벗어나므로 기각; 작은 completion hook 선택 |
| Replacement delegation으로 old Run/approval 이전 | 이번 flow에 필요하지 않아 미지원; exact M6 binding 유지 |
| Public approval/Run API, generic DAG/consent framework | 현재 controlled boundary와 한 step interface로 충분하므로 미도입 |
| Durable workflow/queue, Redis/Kafka/service 분리 | Restart continuation 요구와 실제 flow evidence를 #155/#156에서 판단 |

## 검증과 한계

`AgentRuntimeTests`, `AgentRuntimeArchitectureTests`와 기존
`ProductToolsIntegrationTests`의 Runtime cases가 deterministic fake provider, real MCP registry/engine,
TLS Product HTTP와 MySQL로 authority, approval, duplicate/retry, unknown, cancellation/late accounting,
answer failure를 검증한다. 기존 M6/Router regression을 함께 유지한다. 일반 Backend tests는 외부
생성 provider, credential 또는 유료 호출을 요구하지 않는다. 실행 명령/최종 결과는 PR에 기록한다.

Process restart 후 Run/budget/result continuity, remote provider stop acknowledgement, 자동
mutation replay는 보장하지 않는다. M6 durable approval이 남아도 Run을 재구성하지 않는다.
Fresh actual-provider 세 representative flow는 #155, Ops Product tool은 #154, durable 최종 판단은
#156, generic evaluation/load/release/backup qualification은 M8이 소유한다.

## 재검토 조건

Renewed delegation read re-entry, 새 remotely reachable approval surface, provider retry/fallback,
current non-synthetic disclosure, shared quota 또는 restart continuation이 필요해지면 해당 owner가
security/evidence/decision을 먼저 확정한다. Long latency나 durable approval record의 존재만으로
새 persistence/framework를 채택하지 않는다.
