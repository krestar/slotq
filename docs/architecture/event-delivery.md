# Transactional Event Delivery

Issue [#84](https://github.com/krestar/slotq/issues/84)는
[ADR-0007](../adr/0007-use-transactional-event-record-and-db-delivery.md)의 M3-WP2 production
foundation이다. 같은 Product 배포, DataSource, `JpaTransactionManager`를 사용한다.
Booking capacity release producer와 두 실제 Waitlist promotion handler는 disabled checkpoint까지
구현됐다. durable registration/readiness bootstrap은 후속 checkpoint이고 scheduler 기본값은 disabled다.
실제 effect/receipt/notification과 application lock order는 [Waitlist Promotion](waitlist-promotion.md)을 따른다.
release 없는 기회의 scoped admission/새 요청 append는 [Waitlist promotion requests](waitlist-promotion-requests.md)를 따른다.
`EventRecordQuery.find(tenantId,eventId)`는 원 event/receipt 의미 대조를 위한 nonlocking immutable 조회만
추가하며 global append/cutover locking port나 delivery protocol은 바꾸지 않는다.

## Producer와 durable cutover

`EventAppendService.append`는 `MANDATORY` transaction 안에서 envelope 검증, canonicalization,
immutable collision 검사와 insert를 수행한다. standalone/read-only append는 실패한다.
실패를 caller가 catch해도 outer transaction은 rollback-only가 된다. business write,
boundary counter, event record가 같은 물리 transaction에서 commit/rollback된다.

Producer는 authoritative aggregate ownership에서 `TenantId`를 도출하고 `EventId.newId()`로
별도의 server event identity를 한 번 생성한다. retry에도 같은 identity와 meaning을 사용한다.
append를 `REQUIRES_NEW`, autocommit 또는 async 경로로 분리하면 안 된다. 실제 Booking JPA
write와 append의 물리 transaction 결합은 `BookingCapacityReleaseIntegrationTests`가 검증한다.

### Booking capacity release producer — #96 첫 checkpoint

`ReservationTransitionRecorder`는 ordinary 및 promotional Booking executor가 잠근
Reservation/Allocation의 전이 전 값을 받고, 저장/flush 이후 실제 Allocation
`active → inactive`일 때만 `booking.capacity-released` v1을 기록한다.
public API, System expiry, Offer reject/expiry/reconcile에 별도 publisher를 두지 않는다.

| 실제 저장 전이 | 기록 여부 |
| --- | --- |
| `HELD → CANCELLED`, `HELD → EXPIRED` | active Allocation을 해제한 경우 1건 |
| `CONFIRMED → CANCELLED`, `CONFIRMED → NO_SHOW` | active Allocation을 해제한 경우 1건 |
| `CHECKED_IN → COMPLETED` | active Allocation을 해제한 경우 1건 |
| HOLD 생성, confirm, check-in, 동일 target 반복, GET, effective expiry만 관측 | 기록하지 않음 |
| 실패/outer rollback | Booking 변경과 event/boundary 모두 남지 않음 |

Envelope의 aggregate는 `Reservation`/Reservation ID이고 event ID는 별도의 server UUID다.
Tenant는 잠근 Reservation과 Allocation의 검증된 ownership에서 도출한다. immutable Slot
scope도 non-locking read로 대조하며, ordinary release에 Slot lock을 추가하지 않는다.
payload는 `venueId`, `resourceId`, `slotInventoryId`, `fromState`, `toState`만 포함한다.
`occurredAt`은 해당 command의 단일 `commandNow`이며 foundation이 UTC microsecond로 정규화한다.

`slotq.waitlist.promotion.enabled=false`가 기본값이다. disabled이면 기존 Booking 동작만
유지한다. enabled여도 `CapacityReleaseReadiness` 제공자가 없거나 ready가 아니면 release
transaction을 실패시킨다. 첫 checkpoint에는 handler가 없었고 두 번째 checkpoint에서 실제 두 handler가
추가됐다. production readiness 제공자/registration bootstrap은 아직 없으므로 설정만으로 producer를
활성화할 수 없다. 테스트에서만 readiness와 durable registration을 공급해 실제 M3 worker를 검증한다.

readiness와 별도로 `EventAppendService.appendForActiveRoute`는 기존 MANDATORY 경계 안에서
`event_boundary`를 잠근 뒤 정확한 `waitlist.promotion` / `booking.capacity-released` / v1
active route를 current-read한다. 등록 누락/비활성/불일치를 조용히 건너뛰지 않는다.
route 검증·flush·append 실패를 caller가 catch해도 같은 outer transaction은 rollback-only다.
일반 `append`의 기존 계약은 변경하지 않는다.

lock 순서는 기존 business lock 뒤에 append fence만 덧붙인다.

- ordinary release/expiry: Reservation/Allocation → `event_boundary` → active registration/event
- ordinary confirm의 due expiry: Slot → Reservation/Allocation → append fence
- Offer reject/reconcile: Entry → Offer → Reservation/Allocation → append fence
- Offer accept의 due expiry: Slot → Entry → Offer → Reservation/Allocation → append fence

fence는 outer commit까지 유지한다. 이후 #95의 Offer/Entry 저장은 이미 잠근 row를 갱신하며,
그 단계의 실패도 Booking/event와 함께 rollback한다. registration lifecycle은 business row를
잠그지 않는다. producer transaction 안에서 후보 선택이나 promotion을 실행하지 않는다.

MySQL RR의 기존 promotional capacity locking scan은 조건 평가 중 due Booking row에
대기할 수 있다. 이 producer는 scan이나 isolation을 바꾸지 않고, release가 Slot을 다시
요청하지 않아 `Slot → Booking → append fence` 대기가 순환하지 않는 것을 검증한다.
public Offer accept가 사전 조회에서 이미 만료를 관측하면 기존 reconcile 경로를 사용한다.
Slot-first accept executor와 이 경로를 동일한 lock 경로로 간주하지 않는다.

Append와 `EventRegistrationService.activate/deactivate`는 singleton `event_boundary`를
`FOR UPDATE`로 잠그고 transaction 안에서 sequence를 증가시킨다. 잠금은 outer transaction
종료까지 유지된다. lower boundary transaction이 끝나기 전에 higher boundary가 commit될 수
없다. duplicate append는 counter를 증가시키지 않고 rollback된 allocation도 남지 않는다.
이 counter는 cutover/discovery metadata이며 business/transport/FIFO ordering이 아니다.

등록은 global exact consumer route의 durable history이고, 각 target의 tenant는 event에서 온다.
membership은 `activationBoundary < event.boundarySequence < deactivationBoundary`이며,
종료 boundary가 없으면 upper bound가 없다. 재활성화는 새 registration UUID를 만든다.
비활성화는 신규 event membership만 닫으며 이미 속한 미발견/PENDING/PROCESSING/DEAD의
책임을 없애지 않는다. handler bean 변경은 durable registration을 변경하지 않는다.
unfinished target의 handler 제거·비호환 변경은 drain, 기존 호환성 유지 또는 명시적 migration을
요구한다. 실제 consumer Issue가 semantic compatibility를 검증한다.

## Immutable meaning과 저장 표현

- UUID는 기존 Product와 같은 `BINARY(16)`이다. encoding에 외부 의미를 부여하지 않는다.
- aggregate type, event type, consumer ID는 1–100자 `[A-Za-z0-9._-]`와 `ascii_bin`을 사용한다.
- `occurredAt`은 append 전에 UTC microseconds로 truncate하며 MySQL `DATETIME(6)`의
  1000년–9999년 범위를 검증한다. DB rounding에 identity를 맡기지 않는다.
- payload는 duplicate key/trailing JSON을 거부하고 object key를 정렬한다. array order와
  string/boolean/null 의미는 유지하며 숫자는 exact decimal 값으로 정규화한다.
- canonical JSON text 하나를 `MEDIUMTEXT utf8mb4_bin`에 저장하고 DB `JSON_VALID` CHECK로
  유효성을 강제한다. native MySQL JSON의 숫자 normalization으로 exact decimal 의미를
  잃지 않기 위한 표현이다. 별도의 canonical payload 사본은 없다.
- 같은 event ID의 tenant, aggregate type/identity, route/version, canonical occurredAt/payload가
  다르면 corruption으로 거부하고 caller도 rollback한다. 원 event를 덮어쓰지 않는다.
- PII-free business payload schema는 해당 producer의 책임이다. foundation은 opaque JSON의
  개인정보를 추론하지 않는다. M4 첫 Booking schema가 이를 정의·검증한다.

## Discovery와 worker protocol

`EventDeliveryWorker.runCycle()`은 scheduler와 독립된 내부 진입점이다.

1. `event_discovery` cursor row를 잠그고 boundary sequence 순서로 최대 batch만큼 committed
   event를 읽는다. registration interval을 join해 delivery를 만들고 cursor도 같은 transaction에서
   전진시킨다. `(registrationId,eventId)` unique constraint가 target 중복을 막는다.
   event commit 뒤 materialization 전에 종료돼도 다음 scan이 복구한다.
2. due PENDING/expired PROCESSING candidate를 bounded batch로 발견한다. synchronous handler
   slot이 비었을 때만 개별 claim하며 batch 전체를 미리 lease하지 않는다.
3. claim은 scoped delivery row lock 뒤 **별도 statement의 fresh MySQL UTC**로 eligibility를
   검증한다. attempts/lifetime/token을 증가시키고 PROCESSING과 lease를 commit한다.
   이때부터 한 attempt를 소비하므로 handler 시작 전 crash도 예산을 소비한다.
4. effect transaction은 scoped delivery row를 잠근 뒤 fresh DB UTC로 state/token/lease를 확인한다.
   event와 registration route가 맞아야 한다. handler는 `(consumerId,eventType,schemaVersion)`
   exact match로 resolve한다. target이 없던 event에는 delivery 자체가 없다.
5. handler의 authoritative current-state/tenant guard, effect, 필요한 receipt를 같은 transaction에서
   실행한다. JPA effect를 flush한 뒤 fresh DB UTC로 lease를 재검사하고 같은 token의 DONE을
   guarded update한다. **effect와 DONE은 함께 commit**한다. #80 fixture의 별도 ack window를
   production에 옮기지 않는다. 0-row guarded update는 성공이 아니다.

Registration UUID는 original target provenance다. runtime registry가 비어도 기존 target은
없어지지 않으며 `TARGET_HANDLER_MISSING` DEAD가 된다. 같은 consumer/type의 다른 version만
있으면 `UNSUPPORTED_VERSION`, route 불일치는 `TARGET_ROUTE_CORRUPTION`이다.
payload invariant, tenant, immutable corruption도 각각 구별한다.

## Transaction, timeout과 failure

Worker의 claim/effect/failure/replay transaction은 외부 caller transaction 안에서 시작하지 않는다.
handler의 async 및 join을 깨는 transaction 선언은 registry 구성 시 거부한다. 실행 중 같은
manager의 transaction suspension도 거부한다. handler는 직접 connection/autocommit, 별도 manager,
async·외부 effect를 만들 수 없다. architecture regression과 실제 consumer의 integration test가
이를 보호한다.

Worker transaction의 Spring timeout은 JdbcTemplate/JPA statement에 전파된다. 같은 연결의
`innodb_lock_wait_timeout`과 JDBC network timeout도 설정한다. 종료 뒤 pool 반환 전에 원래 session
값을 복원하며 복원 실패 connection은 폐기한다. 동기 Java callback 자체를 강제 종료하지는 않는다.
handler에 무한 CPU loop, unbounded wait나 외부 I/O를 넣지 않으며 thread timeout을 DB rollback
증거로 사용하지 않는다.

`afterCompletion = ROLLED_BACK`으로 전체 rollback이 확인된 경우에만 durable failure transaction을
열어 token/state/lease를 다시 검증한다. 명시적 transient handler, 확인된 DB deadlock/lock timeout,
query timeout, 명시적 transient connection/resource failure만 남은 예산 안에서 재시도한다.
constraint/schema/임의 programming exception은 `UNCLASSIFIED_FAILURE` DEAD다. diagnostic은
stable code와 제한된 고정 문구만 저장하고 handler/SQL exception 원문은 저장하지 않는다.

MySQL 1213/1205와 NOWAIT의 3572는 `DB_LOCK_TRANSIENT`다. #96 C3의 acyclicity는
application-controlled inverse edge를 금지하며, 동일한 합법적 순서 아래의 InnoDB RR physical
gap/next-key/insert-intention deadlock과 구분한다. 실제 trace로 분류하고 전자는 제거,
후자는 위 bounded retry/전체 rollback/DEAD/replay 규칙으로 처리한다.
[#95 baseline evidence](../experiments/booking-capacity-gap-lock-finding.md)는 opt-in 진단으로 유지한다.
이 분류를 public Booking command에 자동 retry를 도입하는 근거로 사용하지 않는다.

commit/rollback outcome unknown은 rollback으로 추측하지 않고 기존 durable claim을 유지한다.
DB unavailable 시 memory retry loop 없이 현재 cycle이 끝나거나 실패하고 DB 복구 후 재개한다.
실제 commit됐다면 effect/DONE이 함께 남고 rollback됐다면 lease reclaim 뒤 같은 identity로
처리한다. stale owner는 effect/DONE/failure를 commit할 수 없다.

## 기본 설정

| Property (`slotq.events.delivery.` prefix) | 기본값 |
| --- | --- |
| `scheduler-enabled` | `false` |
| `poll-interval` | `PT1S` |
| `max-attempts` | `5` |
| `lease` | `PT30S` |
| `effect-timeout` | `PT10S` |
| `lock-wait` | `PT5S` |
| `batch-size` | `100` |
| `retry-delays` | `PT1S,PT5S,PT30S,PT2M` |

`0 < lock-wait < effect-timeout`, `2 × effect-timeout <= lease`를 강제하며 잘못된 설정은
startup/activation에서 실패한다. lock/effect timeout은 JDBC/MySQL이 적용 가능한 정수 초다.
attempts와 batch는 1–100, effect는 최대 1시간, lease/retry delay는 최대 1일이다.
delay 개수는 attempts−1이며 test는 0 delay를 사용할 수 있다. 마지막 attempt의 실패는 DEAD,
expired claim의 예산 소진은 `CRASH_EXHAUSTED`다. production SLO나 최적값의 주장은 아니다.
기존 전역 `@EnableScheduling`과 HOLD cleanup activation은 유지한다.

## Internal replay와 후속 ownership

`EventReplayService.replay(SystemPrincipal, DeliveryKey, reason)`은 explicit tenant/event/registration과
nonblank reason을 요구하고 scoped DEAD row를 잠근다. fresh DB UTC로 prior state/attempts/lifetime/
token/failure, reason, consumer/target, `TRUSTED_INTERNAL` origin을 audit에 append한 뒤 즉시 due
PENDING으로 바꾼다. cycle attempts=0, token+1, lease/current failure 제거, lifetime/원 event/target/
consumer receipt 보존이 post-state다. audit update/delete API는 없다. SystemPrincipal은 사람 identity가 아니다.

- M3-WP3: 이 production protocol의 별도 JVM crash/restart, DB 장애와 recovery 종료 gate.
  현재 integration의 commit fault injection은 실제 process matrix를 대체하지 않는다.
- M4: 첫 Booking producer/PII-free schema/authoritative tenant/JPA join과 Waitlist consumer,
  실제 business guard, unique effect 또는 좁은 `(consumerId,eventId)` receipt, 역순·중복 검증.
  production generic Inbox/receipt를 미리 만들지 않는다.
- M5: human recovery API/UI, operator 인증·권한·audit, 관측/alert/SLO/runbook.

automatic event/registration/delivery/replay retention, historical backfill surface,
Kafka/Redis/별도 relay service는 추가하지 않는다. Product repository tenant-scope invariant는
유지하고 두 internal Store의 operation과 의존 경계를 별도 architecture test로 검사한다.

## 검증 진입점

Backend directory에서 Java 25와 Docker를 사용한다.

```powershell
.\gradlew.bat test --tests 'com.slotq.events.*' --tests '*EventFoundationArchitectureTests'
.\gradlew.bat test
.\gradlew.bat clean build
```

MySQL 8.4 tests는 append/canonical/cutover, discovery/target, claim/fencing/duplicate,
실제 JDBC/JPA lock timeout·deadlock·blocking SQL timeout, post-lock/final lease, unknown commit
응답, internal replay와 activation을 검증한다. synthetic owner/effect/receipt는 test source에만 있다.
