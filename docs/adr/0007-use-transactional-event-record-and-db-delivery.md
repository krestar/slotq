# ADR-0007: Transactional event record와 DB 기반 전달 경계 사용

- 상태: `Accepted`
- 결정일: 2026-09-09
- 관련 Issue: [#80](https://github.com/krestar/slotq/issues/80)
- 근거: [실험 및 완료조건 대조](../experiments/event-delivery-boundaries.md),
  [clean 원자료](../experiments/events/clean/report.json)
- Production 구현: [#84 runtime/schema/configuration](../architecture/event-delivery.md).
  WP2는 effect와 DONE을 하나의 transaction으로 commit하며 아래 #80 fixture의 별도 ack window를 도입하지 않는다.

## 비교와 결정

실제 MySQL 8.4.11, REPEATABLE-READ, Hikari pool 10에서 같은 seed와 fixture를 사용해
동기 처리, commit 이후 process-local publish, business transaction 안 durable append를
비교했다. 각 후보는 같은 다섯 fault를 세 번씩 실행했다. 별도 JVM의 `Runtime.halt(80)`
뒤 parent의 DB 조회와 새 JVM의 durable record scan으로 crash 경계를 관측했다.

| 후보 | committed business | 복구 후 effect | 복구 source 없는 유실 | commit 직후 crash 유실 |
| --- | ---: | ---: | ---: | ---: |
| 동기 처리 | 9 | 9 | 0 | 0 |
| commit 후 direct publish | 12 | 6 | 6 | 3 |
| transactional durable record | 12 | 12 | 0 | 0 |

**M3는 business state와 durable event record를 같은 MySQL transaction에 저장하는
Transactional Outbox 계열을 선택한다. 전달은 같은 Product 배포 안의 DB polling과
in-process consumer 호출로 시작한다. Kafka, 별도 broker/service는 도입하지 않는다.**

동기 처리는 정합성 실패 후보가 아니다. 그러나 handler failure 세 건이 business 자체를
rollback시켰고 100ms handler delay가 producer transaction에 포함됐다. Booking이 선택적인
Waitlist/Notification 후속 작업 실패에 종속되지 않는 ADR-0002와 domain-model의 의존
방향, committed change의 독립적인 복구 요구를 적용할 때 M3 기본 전달 경계로 선택하지
않는다. 같은 원자적 business change 안의 동기 public-port 호출은 계속 허용한다.

direct publish는 commit 직후 세 실제 process 종료에서 business만 남았다. process memory나
호출자의 재시도가 durable recovery source를 대신하지 못한다. 이를 복구하려고 business
table에서 event를 재구성하면 새로운 change capture 계약이 필요하므로 이 후보의 기능으로
가정하지 않는다.

durable record는 producer transaction에 insert 비용과 저장 공간을 추가한다. duplicate를
흡수하는 consumer 제약과 복구 상태도 필요하다. 그 비용을 감수하면 handler 실패가
business commit을 취소하지 않고, 재시작 뒤 처리 대상을 DB에서 다시 찾을 수 있었다.
단일 run의 latency는 production SLO나 일반적인 성능 우위가 아니다.

## Mechanism-neutral 결과 계약

- authoritative source는 committed MySQL business state와 event/reliability state다.
  memory, log, HTTP response, ack만으로 성공·유실을 판정하지 않는다.
- committed business change의 event는 recovery 후 effect 완료 또는 조회 가능한
  pending/terminal failure로 설명돼야 한다. terminal failure를 성공이나 유실로 숨기지 않는다.
- business rollback은 해당 durable event도 rollback한다.
- redelivery/retry/replay는 같은 logical event의 business effect를 추가 commit하지 않는다.
- 자동 시도는 유한하며 exhaustion과 non-retryable failure는 durable하게 남는다.
- recovery와 replay도 tenant authority와 immutable identity를 유지한다.

## Producer atomicity와 transaction guard

producer는 authoritative aggregate/storage ownership에서 tenant를 파생하고, business write와
event append를 **동일 DataSource와 transaction manager가 관리하는 실제 transaction**에서
수행한다. append는 caller transaction을 요구하며 없으면 거부한다. independent transaction,
REQUIRES_NEW, autocommit 또는 async append를 허용하지 않는다. exception을 삼켜 business만
commit하는 경로도 금지한다.

실험의 `TransactionTemplate` REQUIRED 참여는 실제 outer rollback에서 business/event 모두
0인 결과로 검증했다. standalone append도 거부했다. 이는 JDBC fixture의 검증이며, M4 첫
production producer가 실제 JPA business write와 append의 공유 connection/outer rollback을
다시 검증해야 한다. 현재 Product JPA 경로를 이미 event와 연결했다고 주장하지 않는다.

## Identity, envelope와 routing

- server 생성 stable opaque eventId를 사용한다. ReservationId, HOLD key, fingerprint,
  correlationId와 별개다. fixture만 고정 seed UUID를 사용한다.
- immutable 의미는 eventId, tenantId, aggregate identity, eventType, 양의 schemaVersion,
  UTC occurredAt, 개인정보 없는 payload 전체다. retry/replay에 새 ID를 발급하지 않는다.
- routing은 명시적으로 등록된 `(eventType, schemaVersion)` exact match다. unknown type/version과
  payload invariant 위반은 non-retryable이다. Java serialization과 범용 upcaster는 사용하지 않는다.
- JSON 공백/property order는 의미가 아니다. 실제 MySQL JSON round-trip에서 이를 문자열로
  비교한 초기 fixture가 오판했으므로 schema-aware 의미 비교를 한다. 같은 ID의 다른 의미는
  corruption이며 정상 duplicate로 skip하지 않는다. append 충돌과 consumer duplicate 판정
  모두 immutable 의미를 검사해야 한다. 기록된 원 event를 덮어쓰지 않는다.
- syntactically invalid JSON은 append/DB rejection으로 business와 함께 rollback한다.
  durable poison은 valid JSON의 unsupported type/version 또는 invariant 위반이다.

## Consumer idempotency와 ordering

전달은 at-least-once다. consumer별 event 적용 identity와 DB side effect는 같은 transaction에
있어야 한다. fixture에서는 eventId PK의 business effect row 자체가 적용 기록이다. 순차,
20ms 후 지연, 4-worker concurrent first delivery가 모두 effect 한 row만 남겼다. effect commit
직후 ack 전 JVM 종료에서도 재전달 후 한 row였다.

범용 Inbox/message buffer는 선택하지 않는다. consumer의 business unique row가 event identity와
immutable 의미를 보존하면 그것을 사용한다. 그렇지 않은 consumer는 좁은
`(consumerId, eventId)` 적용 receipt를 side effect와 함께 저장해야 한다. receipt에는 tenant와
immutable 의미 검증 근거가 필요하며 receipt만 먼저 독립 commit해서는 안 된다. 이 조건은
후속 consumer 등록 계약의 의무이고 모든 production aggregate를 foundation이 소유한다는
뜻이 아니다. DB 외부 effect의 exactly-once까지 확장하지 않는다.

**global/tenant/aggregate transport ordering은 제공하지 않는다.** fixture의 역순 2→1은
authoritative owner의 current state를 읽고 projection을 2에 유지했다. event는 오래된 state를
복원하는 명령이 아니라 재평가 신호다. 현재 Product의 capacity/lifecycle guard와 M4의
business unique constraint·promotional HOLD 정합성을 consumer가 호출해야 한다. event의
sequence만 보고 capacity를 증가시키거나 다음 후보를 무조건 승급하면 이 보장이 성립하지
않는다. 실제 Waitlist 후보 FIFO는 business ordering이며 transport FIFO와 별개다.

M4는 첫 producer와 consumer를 함께 연결하며 역순·중복 상태에서 실제 Booking guard와
offer uniqueness를 검증한다. DB current-state 재평가로 표현할 수 없는 순서 의존 delta가
생기면 해당 aggregate/consumer 단위 ordering을 새로 검토한다.

## 선택 topology의 retry/recovery protocol

M3-WP2는 event 불변 record와 delivery state를 분리한다. event append만 business transaction에
필수다. delivery row는 event scan으로 idempotently 생성할 수 있어야 하며, row가 없다는
이유로 committed event를 누락하지 않는다. 한 event의 등록된 consumer 대상은 명시적이어야
한다. fan-out/group framework는 현재 입력이 아니다.

consumer 추가·제거가 이미 committed된 event의 대상 집합이나 기존 PENDING/DEAD의
처리 책임을 암묵적으로 변경해서는 안 된다. activation/cutover 의미를 명시하여 restart
scan도 같은 대상 의미를 복구해야 한다. 신규 consumer의 historical event 적용은 자동
등록 효과가 아닌 명시적인 replay/backfill 결정이다. 구체적인 저장 방식과 schema는
M3-WP2가 선택한다.

crash도 유한 시도 예산에 포함하기 위해 handler 실행 전에 짧은 transaction으로 attempt와
monotonic fencing token을 durable하게 예약한다. 실험은 이 필요를 검증하는 최소 claim/lease
probe이며 production scheduler나 전체 runtime은 아니다.

| 현재 상태 / 조건 | durable 전이 | 의미 |
| --- | --- | --- |
| event 발견, delivery 없음 | PENDING 생성 | restart scan으로 재생성 가능 |
| PENDING이며 due, attempts < limit | PROCESSING, attempts/lifetime +1, token +1, lease 설정 | claim commit부터 한 시도 소모 |
| PROCESSING lease 만료, 예산 남음 | 새 PROCESSING, attempts/lifetime +1, token +1 | 죽은 owner를 reclaim |
| PROCESSING lease 만료, 예산 소진 | DEAD + crash exhaustion | handler 시작 전 crash도 무한 반복하지 않음 |
| 유효 owner의 effect commit 및 ack | DONE | ack는 같은 token에 한정 |
| 유효 owner의 확정 rollback + retryable failure | PENDING + nextAttemptAt, 또는 DEAD | 예산 남을 때만 재시도 |
| 유효 owner의 non-retryable failure | DEAD + stable failure code | 자동 retry 없음 |
| DEAD + 허용된 replay | PENDING, cycle attempts=0, token +1, audit append | lifetime count·event·receipt 유지 |

lease/nextAttemptAt의 권위 시각은 MySQL UTC다. row lock을 얻은 뒤 fresh DB time으로 eligibility를
평가한다. effect transaction은 delivery row를 먼저 잠그고 state/token/lease를 검증한 뒤
consumer의 current-state guard와 effect/receipt를 함께 처리한다. stale token은 effect를
commit하지 못한다. lock은 effect transaction 끝까지 유지한다. ack/failure update 역시
state/token/lease 조건을 확인하고 0-row update를 성공으로 간주하지 않는다. lease가 지난
old owner의 ack는 새 owner의 상태를 덮어쓰지 않는다. lease 연장을 전제로 하지 않는다.

claim 이후 crash와 effect commit 이후 ack 전 crash를 실제 JVM 종료로 확인했다. 후자는
effect가 이미 하나이고 delivery는 PROCESSING이다. reclaim 후 같은 receipt를 확인해 DONE으로
끝난다. claim 직후 세 번의 crash는 attempts=3인 DEAD로 종료됐다. 실험의 limit=3,
lease=300ms, backoff=0은 test 값이다. WP2는 양의 유한 limit/lease와 bounded delay,
poll interval/batch/timeouts를 설정·검증하고 그 값을 production SLO로 주장하지 않는다.

retryable은 확정 rollback된 일시적 DB deadlock/lock timeout/connection 장애와 명시적인
transient handler failure다. unknown type/version, payload invariant, tenant mismatch,
immutable identity corruption은 non-retryable이다. schema/constraint/programming 오류를
무조건 transient로 분류하지 않는다. fixture는 주입한 transient만 실행했고 DB infrastructure
분류의 production 구현은 WP2 입력이다. Product HTTP의 기존 500 mapping은 바꾸지 않는다.

commit 결과 미상·process crash는 실패 rollback으로 단정하지 않는다. durable claim을 유지하고
lease/recovery 및 receipt 조회로 재판정한다. DB 자체가 불가용해 failure를 저장하지 못하면
memory retry loop를 돌리지 않으며, DB 복구 후 기존 claim/event부터 재개한다. 한 cycle의
durable claim 수가 limit을 넘지 않아야 한다. DEAD는 event identity, 원인, 마지막 오류와
cycle/lifetime 시도 수를 조회할 수 있어야 한다.

M3에서는 event/delivery, consumer receipt와 replay history의 automatic retention/cleanup을
도입하지 않는다. replay/redelivery horizon과 운영 보존 요구가 정의된 뒤 삭제 순서와
참조·중복 방지·restart scan의 integrity 계약을 별도로 결정한다. 보존 기간 자체는
이번 ADR에서 선결정하지 않는다.

## Tenant와 privileged replay

fixture consumer는 authoritative owner를 잠그고 tenant 일치를 확인한다. forged event는
다른 tenant effect를 commit하지 않았다. fixture FK는 이 fixture가 소유한 관계만 보호한다.
production foundation이 Booking/Waitlist의 모든 aggregate FK를 대신 소유하지 않는다.

M3는 **trusted internal recovery primitive**를 제공한다. 명시적인 tenant scope, eventId,
nonblank replay reason과 privilege가 필요하다. transaction 안에서 scoped DEAD row를 잠그고
append-only durable history를 기록한 뒤 retry cycle을 연다. history에는 tenant/event,
reason, DB 시각, 이전 상태·cycle attempts/lifetime attempts, internal recovery origin을 남긴다.
event payload, identity, consumer receipt와 lifetime count는 reset하지 않는다. poison의
immutable payload를 replay로 고치지 않는다. 호환 handler 배포 등 원인 해결 뒤 재실행한다.

fixture boolean privilege나 현재 SystemPrincipal은 사람 identity가 아니다. M3에 human operator
identity, HTTP replay API, UI/dashboard와 완전한 운영 권한 모델을 추가하지 않는다. 사람
entrypoint의 인증·권한·실명 audit 연계와 runbook은 M5가 소유한다. 그 entrypoint도 같은
tenant-safe primitive를 사용해야 한다. 이유 없는 privileged 전체-tenant replay는 허용하지 않는다.

## External timeout과 후속 ownership

M3의 source of truth와 atomicity는 MySQL DB side effect까지다. 실제 provider가 없는 generic
foundation에서 가짜 adapter의 idempotency를 검증해 SMS/email 중복 방지를 증명할 수 없다.
따라서 response-before/after timeout fault는 **첫 실제 external-side-effect/provider Issue**가
소유한다. 실제 provider의 도입 Milestone은 여기서 고정하지 않는다. M4가 notification
request contract만 다루고 provider를 연결하지 않으면 provider 검증을 선도입하지 않는다.
해당 timeout 검증은 실제 외부 전송 완료를 선언하기 전 필수 gate다.

그 Issue는 provider idempotency key와 retention, ambiguous outcome 조회/재전송, response
유실 전후 중복 effect, 지원하지 않는 provider의 보장 한계를 test adapter와 실제 계약으로
검증해야 한다. experiment matrix에서 이 항목을 N/A로 삭제하지 않는다. M3에는 실제
SMS/email provider를 도입하지 않는다.

## 후속 입력과 재검토 trigger

- M3-WP2: 위 전이·fencing·failure taxonomy, transaction join guard, scoped recovery/audit,
  명시적 consumer registration, schema/migration, bounded runtime 설정의 production 구현.
- M3-WP3: WP2의 merged protocol에서 process crash/restart, stale owner, DB 불가용,
  partial commit/ack ambiguity와 backlog drain 종료 gate. #80 fixture가 이를 대체하지 않는다.
- M4: 최초 실제 Booking event schema/trigger + business append + Waitlist consumer를 함께 연결.
  subscriber 없는 production event backlog를 먼저 만들지 않는다.
- M5: human replay surface, 운영 authorization/audit, dashboard/alert/trace/SLO/runbook.

DB polling의 fan-out, consumer isolation, 처리량, retention 또는 독립 배포 요구를 실제로
충족하지 못한다는 측정이 생기면 broker/다른 mechanism을 비교한다. 현재는 그런 근거가 없다.
lease/receipt 비용이 local DB 처리에서 병목이면 같은 outcome invariant를 지키는 co-transaction
delivery 등 더 단순한 topology도 재비교한다. 선택 후보의 industry 관행은 채택 근거가 아니다.
