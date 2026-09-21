# Waitlist Promotion Effect

Issue [#96](https://github.com/krestar/slotq/issues/96)의 두 번째 checkpoint다.
Booking release producer 위에 실제 두 handler와 event-level effect를 구현한다.
request admission/discovery, maintenance, durable registration/readiness bootstrap 및 scheduler 활성화는
후속 checkpoint다. #96 전체 완료나 실제 process crash/restart evidence 완료를 뜻하지 않는다.

## 경계와 route

| Integration handler | Exact route | Aggregate | v1 payload |
| --- | --- | --- | --- |
| `BookingCapacityReleasedHandler` | `waitlist.promotion` / `booking.capacity-released` / 1 | Reservation / Reservation UUID | venueId, resourceId, slotInventoryId, fromState, toState |
| `WaitlistPromotionRequestedHandler` | `waitlist.promotion` / `waitlist.promotion-requested` / 1 | SlotInventory / Slot UUID | venueId, resourceId, slotInventoryId |

`com.slotq.integration.waitlist`가 envelope/JSON/route vocabulary와 M3 failure mapping을 소유한다.
두 top-level `EventHandler`는 `MANDATORY`이고 같은 `WaitlistPromotionUseCase`로 위임한다.
이 public port는 event-neutral typed command/result를 사용한다. Waitlist는 events foundation이나
Booking persistence/Reservation aggregate에 의존하지 않고, Booking은 Waitlist type을 참조하지 않는다.
events foundation에도 business concrete type 의존을 추가하지 않는다.

adapter는 정확한 allowlist, canonical UUID, aggregate와 release state-pair를 검증한다.
Booking port가 잠근 Slot ownership 및 release의 원본 Reservation immutable scope를 검증한다.
source의 mutable state가 event 당시 state와 같아야 한다는 guard는 없다. source를 재해제하거나
delta로 capacity를 늘리지 않는다. missing/잘못된 scope는 PAYLOAD_INVALID, tenant 불일치는
TENANT_MISMATCH, 동일 logical identity의 의미 변경은 IDENTITY_CORRUPTION이다.
infra 예외를 정상 no-op로 삼키지 않는다.

## Effect sequence / transaction

```text
M3 delivery lock + fresh owner/lease 검증 + target 의미/route 검증
  → receipt insert-first claim/current lock + immutable 의미 검증
  → Booking Slot FOR UPDATE
  → immutable release ownership + scoped current Tenant/Venue/Resource/Policy
  → eligible FIFO current query + Entry lock/revalidation
  → 기존 Booking current capacity guard
  → 새 promotional Reservation/Allocation 저장 + JPA flush
  → Offer INSERT + Entry OFFERED
  → notification request INSERT + receipt completion
  → M3 final JPA flush + fresh MySQL lease/token 검증 + guarded DONE
  → 한 physical commit
```

처음부터 끝까지 같은 Product `JpaTransactionManager`/DataSource transaction이다.
새 Booking dynamic-candidate overload는 Slot과 current Resource context 이후에만 selector를 호출한다.
기존 fixed `createTarget`, Customer Offer API, ordinary lifecycle 및 capacity query는 변경하지 않는다.
commandNow는 promotion command 진입 전 한 번 캡처하며 business guard/TTL에 일관되게 사용한다.
M3의 최종 fresh DB clock은 별개다.

정상 결과는 `PROMOTED`, `NO_CAPACITY`, `NO_CANDIDATE`, `NOT_ELIGIBLE`, `SLOT_PAST`, `DEFERRED`다.
모두 receipt와 DONE을 함께 완료하며 no-op를 rollback-only 예외로 바꾸지 않는다.
SELECTED가 아닌 결과는 HOLD를 만들지 않는다. DB/constraint/flush/fencing/lease failure에서는
receipt·Reservation·Allocation·Offer·Entry·notification·DONE 전부 rollback한다.
commit/rollback unknown은 M3가 기존 claim/lease recovery에 맡기며 성공이나 확정 실패로 위조하지 않는다.

## FIFO / current-read / bounds

M3 target 일반 SELECT로 생긴 early RR snapshot이 있어도 current context/capacity를 사용한다.
current Tenant/Venue/Policy는 기존 scoped locking read, Resource는 기존 `NOWAIT`를 보존한다.
source Reservation의 nonlocking read는 immutable ownership만 전달하고 mutable state/capacity에는 쓰지 않는다.

후보 query는 `(tenant,venue,startsAt,endsAt,partySize)` identity index에서 정확한 시간대와
`partySize <= current seatingCapacity`의 Demand를 prefilter한다. Demand도 shared current-read하여
early snapshot 이후 생긴 수요를 놓치지 않는다. 각 eligible Demand의 WAITING head를 LATERAL LIMIT 1로
current-read/lock하고, head 중 `(joined_at,id)` DB 순서의 최소 하나를 반환한다.

- Entry index는 `(demand_id,state,joined_at,id)`이며 inner ORDER BY에도 전체 index prefix를 명시한다.
  실제 MySQL EXPLAIN에서 inner filesort가 없고, 같은 Demand의 대기 Entry 9개에서도 primary X lock은
  head 한 개임을 검사한다. 서로 다른 party-size Demand 3개/Entry 15개에서도 head 3개만 잠그고
  전역 joinedAt/ID FIFO를 선택함을 검증한다. outer head 정렬을 queue 전체 Entry aggregate로 바꾸지 않는다.
- 물리 Entry lock은 **eligible Demand당 head 최대 하나**다. 여러 party-size Demand가 적합하면 여러
  head를 잠글 수 있으며 event당 한 Offer 또는 Java candidate-batch가 DB scan row 수를 뜻하지 않는다.
  다른 시간대/부적합 party-size의 Entry prefix를 먼저 잠그거나 historical Reservation 전체를 잠그는
  새 query를 추가하지 않는다.
- Demand와 Entry는 `NOWAIT`다. 선행 WAITING lock을 SKIP LOCKED로 건너뛰지 않는다.
  #94 Demand→Entry와 #95 Entry→Demand 사이 역방향 **대기**를 만들지 않고 effect를 transient 실패시킨다.
  lock을 가진 후보가 cancel/다른 Slot promotion을 commit한 뒤 새 attempt는 current WAITING을 재선택한다.
- scope/시간대/partySize/current Entry state를 잠금 안에서 검증한다. 부적합/terminal prefix는 SQL
  prefilter로 제외하므로 batch보다 큰 prefix 뒤 유효 후보도 계속 같은 첫 페이지에 갇히지 않는다.
- `candidate-batch-size`는 Java 후보 검증 반복 상한 1–100(기본 32), `candidate-time-limit`은
  양수 최대 5초(기본 PT1S)다. query 후 예산을 넘기면 DEFERRED로 완료한다. JDBC query/network/lock은
  M3 effect timeout에 묶인다. 이 설정을 SQL 전체 scan row 수의 hard cap이라고 주장하지 않는다.
  DEFERRED의 새 durable request 생성은 후속 discovery checkpoint가 소유한다.

## Receipt / notification

V12의 receipt PK는 `(tenantId,waitlist.promotion,eventId)`다. insert-first + 의미를 덮어쓰지 않는
duplicate update 뒤 current-read한다. signal/source/occurredAt/전체 payload 의미를 typed column으로
보존하고 비교한다. route/version/aggregate는 adapter의 exact v1 해석에 포함된다.
generation, attempt, fencing token은 effect identity가 아니다.

새 claim은 nullable outcome으로 시작하지만 한 joined command 안에서 complete 또는 rollback한다.
receipt를 먼저 독립 commit하지 않는다. completed receipt는 기존 결과만 반환한다. no-op 뒤 새 수요,
terminal Offer 뒤 재전달, 새 worker/fencing token에서도 같은 event로 두 번째 Offer를 만들지 않는다.
새 기회에는 후속 discovery가 새로운 event ID를 발급해야 한다.

notification은 `(tenant,Offer,OFFER_AVAILABLE)`당 DB 요청 한 건이다. Entry/Offer/Reservation과
expiresAt만 기록한다. receipt completion과 notification은 scoped Offer FK로 같은 effect를 참조한다.
외부 HTTP/SMTP/SMS 호출, 고객 수신 성공, 별도 consumer/dispatch/cleanup은 구현하지 않는다.

## Application-controlled lock graph와 physical lock 분류

| 경로 | Application lock / write 방향 |
| --- | --- |
| ordinary HOLD / CONFIRM | 기존 Slot-first / Slot→Reservation; 새 retry 없음 |
| ordinary release / HOLD expiry | 기존 Reservation/Allocation→event_boundary; Slot/Entry/Offer 요청 없음 |
| #95 reject / expiry / reconcile | 기존 Entry→Offer→Reservation/Allocation→event_boundary; 새 Slot 요청 없음 |
| #95 fixed create / accept | 기존 Slot-first와 current capacity, 기존 상대 순서 유지 |
| M3 promotion | delivery→receipt→Slot→current context→current candidate→capacity/new Booking pair→Offer/Entry→notification/receipt→DONE |
| registration lifecycle | event_boundary→registration; business row 요청 없음 |

promotion은 append/registration/다음 candidate transaction을 실행하지 않는다. 따라서
event_boundary→business 방향을 추가하지 않는다. release 뒤 같은 transaction에서 promotion을
연속 실행하지 않으며 commit한 event를 새 delivery effect가 처리한다. Resource NOWAIT는 기존
Resource→Slot 생성 경로와 반대 대기를 방지한다. 새 receipt에는 claim 단계의 business FK를 두지 않아
Slot 전에 business parent를 선점하지 않는다. completion FK는 이미 생성/잠근 effect만 참조한다.

실제 MySQL `promotionAndRegistrationHaveNoEventBoundaryToBusinessInverseEdge`는 외부 transaction이
append fence를 잡은 동안 registration 대기자의 lock이 foundation에만 있고, promotion의 JPA/JDBC
effect와 DONE이 완료되는 것을 확인한다. `performance_schema.data_locks`에서 Slot/Entry/receipt/
delivery, FK scope index와 capacity secondary-index 잠금을 수집한다. uncontended INSERT의 implicit
lock이 모두 data_locks record로 나타난다고 가정하지 않으며 저장된 effect pair/FK oracle도 함께 쓴다.
producer 쪽은 `BookingCapacityReleaseIntegrationTests`의 실제 release/reject/expiry 및 fence 교차 증거를 사용한다.
2026-09-21 MySQL 8.4.11 / RR 실행의 [registration fence 원자료](../experiments/evidence/promotion-effect-2026-09-21/REGISTRATION_FENCE.txt)를
보존했다. main `0ee31ab2` 위 Commit 1 `1296912`와 이 checkpoint의 production/test source를 사용했으며,
fixture ID는 전부 일회용 synthetic 값이다. 테스트 실행 시 `build/waitlist-promotion-lock-evidence.txt`에 재생성된다.

최신 C3는 application inverse edge와 InnoDB RR의 physical gap/next-key/insert-intention deadlock을
구분한다. [2026-09-21 main #95 baseline](../experiments/booking-capacity-gap-lock-finding.md)의 다른 Slot
1213은 후자다. 그것만으로 Booking capacity/Slot-local authority/isolation을 재설계하지 않는다.
추가 deadlock이 발견되면 실제 trace로 다시 분류한다. application cycle이면 제거 대상이며,
engine transient이면 기존 M3 전체 rollback/bounded retry/DEAD/trusted replay 규칙을 적용한다.
public Booking 명령에 worker retry를 역으로 추가하지 않는다.

`independentMysql1213RollsBackWholeEffectAndBoundedRetryCommitsOnce`는 capacity-gap 재현에 의존하지
않고 test-only 실제 InnoDB row cycle로 production effect transaction을 victim으로 만든다.
매 attempt의 `INNODB_METRICS.lock_deadlocks` 증가, effect 전체 rollback, PENDING→retry 성공 및
3회 소진→DEAD→자동 claim 불가→trusted replay 후 단일 effect/DONE을 검증한다.
각 실패 attempt의 raw trace는 `build/waitlist-promotion-deadlocks/`에 재생성한다.
[3회 소진의 마지막 attempt 원자료](../experiments/evidence/promotion-effect-2026-09-21/INDEPENDENT_1213_FINAL_ATTEMPT.txt)는
2026-09-21 06:55 UTC 실행이다. 외부 test transaction T2615가 probe PK 1을 보유하고 PK 2를 요청하고,
실제 effect transaction T2618이 PK 2를 보유한 채 PK 1을 요청해 후자가 victim이 됐다.
이 역순은 failure 주입용 **test-only probe**에만 존재하며 production application cycle 증거가 아니다.
gap이 발생해야 성공하는 baseline fixture는 환경변수 `SLOTQ_CAPACITY_LOCK_EVIDENCE=true`의
opt-in 진단으로만 유지한다.

## Activation / 후속 ownership

handler bean은 존재하지만 durable registration을 만들지 않는다. producer는 기본 disabled이며
enabled라도 production readiness provider가 없으면 release를 fail-closed한다. event scheduler도
기본 disabled다. 이 checkpoint의 테스트만 readiness/registration을 공급한다.

후속 checkpoint는 request admission/discovery → maintenance → durable bootstrap/activation 및
실제 business process crash/DB outage evidence 순서다. 현재 worker 교체 테스트는 같은 DB의
claim/token/terminal receipt를 새 worker 객체가 재사용하는 component 증거이며 별도 JVM 종료 실험이 아니다.
기존 M3 synthetic recovery를 실제 Waitlist process evidence라고 재사용하지 않는다.

## Focused 검증

`WaitlistPromotionDeliveryIntegrationTests`는 두 route/illegal schema·ownership, FIFO/tie-break/invalid
prefix>batch/DEFERRED, early RR snapshot 이후 변화, same/different Slot worker와 ordinary
HOLD/CONFIRM/replacement 양 ordering, terminal replay/worker 교체, JPA flush/JDBC write/receipt/
notification/DONE rollback 및 lease/fencing loss를 검증한다.

```powershell
.\gradlew.bat test --tests '*WaitlistPromotionDeliveryIntegrationTests' --tests '*EventDeliveryIntegrationTests' --tests '*WaitlistRegistrationIntegrationTests' --tests '*WaitlistArchitectureTests' --tests '*EventFoundationArchitectureTests' --tests '*BookingCapacityReleaseIntegrationTests' --tests '*DeliveryFailureTests'
.\gradlew.bat test --tests '*ReservationHoldIntegrationTests' --tests '*ReservationConfirmExpiryIntegrationTests' --tests '*ReservationTransitionIntegrationTests' --tests '*ProductApiErrorContractTests' --tests '*ReservationApplicationBoundaryTests'
```

전체 Backend `test`와 `clean build`는 최종 PR gate에서 별도로 수행한다.

2026-09-21 checkpoint 검증 결과(분리 실행, 중복 재실행 제외):

| Focused suite | 성공 |
| --- | ---: |
| WaitlistPromotionDeliveryIntegrationTests | 62 |
| EventDeliveryIntegrationTests / DeliveryFailureTests | 19 / 2 |
| WaitlistRegistrationIntegrationTests | 21 |
| BookingCapacityReleaseIntegrationTests | 36 |
| WaitlistArchitectureTests / EventFoundationArchitectureTests | 5 / 8 |
| ReservationHold / ConfirmExpiry / Transition IntegrationTests | 14 / 9 / 14 |
| ProductApiErrorContractTests / ReservationApplicationBoundaryTests | 3 / 3 |

총 196건 성공, opt-in capacity-gap 진단 1건은 기본 실행에서 제외했다. 첫 실행은 Docker 미기동으로
중단되어 엔진을 시작한 뒤 재실행했다. 새 fence 증거 테스트의 initial oracle은 uncontended INSERT의
implicit lock도 `data_locks` record에 나타난다고 잘못 가정해 실패했다. FK/명시적 lock과 저장된 effect를
검증하도록 보정한 해당 1건 및 이후 추가/보강한 FIFO·1213 회귀를 각각 재실행해 성공했다.
이 수치는 전체 Backend/clean build 또는 별도 JVM process crash 검증의 성공을 뜻하지 않는다.
