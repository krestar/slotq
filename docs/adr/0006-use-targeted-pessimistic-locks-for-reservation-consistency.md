# ADR-0006: Reservation 정합성 경계에 대상 row pessimistic lock 사용

- 상태: `Accepted`
- 결정일: 2026-09-02
- 관련 Issue: [#16](https://github.com/krestar/slotq/issues/16), [#70](https://github.com/krestar/slotq/issues/70), [#88](https://github.com/krestar/slotq/issues/88)
- 비교 증거: [Reservation 동시성 전략 비교](../experiments/concurrency-strategy-comparison.md)
- 종료 감사 재검증: 2026-09-08, revision
  `e9faf83e091ab63d8f833bedd990355288054662`, 두 run 모두 `dirty=false`

## 맥락

기존 HOLD는 effective capacity를 조회한 뒤 서로 다른 Reservation과 Allocation row를
insert했다. Reservation 자체에 version을 두어도 서로 다른 새 row의 race는 감지하지
못한다. #15의 MySQL baseline은 이 race가 실제 Product 경로에서 invariant를 깨뜨림을
보였다.

Lifecycle command는 같은 Reservation snapshot을 읽고 상태와 Allocation을 저장하므로,
동시에 실행된 stale writer가 더 최신 committed state를 덮어쓸 수 있었다. Capacity와
lifecycle은 contention row가 다르므로 같은 mechanism을 쓴다고 미리 가정하지 않고 각각
optimistic/pessimistic 적용 가능성을 검토했다.

## 후보와 측정

Capacity optimistic 후보는 SlotInventory row의 별도 version을 capacity check/write
transaction 끝에서 compare-and-increment하고 최대 2회 새 transaction으로 시도했다.
Reservation/Allocation/#17 reliability state는 stale 검출 시 함께 rollback했다. Lifecycle에
같은 방식을 적용하려면 Reservation version migration, version propagation과 transaction
밖 bounded retry가 별도로 필요하다. 재현용 version column, repository decorator와 retry
orchestration은 test source와 일회용 database에만 두고 Product artifact에는 포함하지 않았다.

Pessimistic 후보는 capacity에는 SlotInventory 한 row, lifecycle에는 Reservation 한 row를
`FOR UPDATE`로 읽었다. #15와 동일한 10 clients × 5 iterations, seed `15001`, MySQL
`8.4.11`, `REPEATABLE-READ`, Hikari pool 10에서 capacity 후보를 비교했다. 두 후보 모두
invariant violation 0, successful HOLD 5, `CAPACITY_UNAVAILABLE` 45, system failure/timeout
0, deadlock 0이었다.

Optimistic은 79.29 req/s, P50/P95/P99 85.31/256.33/262.68ms, stale retry 45,
row-lock wait 45회/1010ms였다. Pessimistic은 36.01 req/s,
P50/P95/P99 166.77/392.05/440.25ms, retry 0, row-lock wait 45회/6095ms였다.
이는 warm-up을 분리하지 않은 단일 local run이며 production 성능 보장이 아니다.

## 결정

### Capacity boundary

HOLD transaction의 첫 database read로 path의 VenueId와 SlotInventoryId에 정확히 일치하는
SlotInventory 한 row를 `PESSIMISTIC_WRITE`로 잠근다. lock을 얻은 뒤 기존 tenant/Venue/
Resource/time/partySize guard와 effective capacity predicate를 평가하고 Reservation,
Allocation, 선택적인 #17 idempotency completion을 같은 transaction에서 저장한다.

MySQL `REPEATABLE-READ`에서 일반 consistent read 뒤 lock을 얻으면 이미 만들어진 snapshot을
capacity query가 재사용할 수 있다. 실제 prototype에서 이 순서가 correctness를 깨뜨렸으므로
Slot locking current read는 transaction의 첫 DB read여야 한다.

lock 범위는 같은 SlotInventory row뿐이다. 다른 Slot, Venue나 Tenant를 잠그지 않는다.
한 transaction의 write order는 Slot lock, 선택적인 idempotency claim, Reservation insert,
Allocation insert, idempotency completion이다.

### Lifecycle boundary

권한 검증을 통과한 CONFIRM transaction은 첫 read에서 대상 Slot 한 row를
`PESSIMISTIC_WRITE`로 잠근 뒤 대상 Reservation 한 row를 같은 방식으로 잠근다.
CONFIRM 외 lifecycle command와 내부 expiry는 기존대로 대상 Reservation 한 row를
첫 read에서 잠근다. 그 뒤 Allocation을 읽고 authoritative current state/time guard를
평가한 다음 Reservation과 Allocation을 같은 transaction에서 저장한다. CONFIRM이
HELD에서 전이하려면 자신을 제외한 effective capacity consumer가 없어야 한다.
아래 #88 보정 기록에 Slot을 공유하는 이유와 production lock order를 설명한다.

직렬화 뒤 current state에서 결과가 확정되면 기존 M1 code를 그대로 사용한다.
`CAPACITY_UNAVAILABLE`, `HOLD_EXPIRED`, `CANCELLATION_WINDOW_CLOSED`,
`RESERVATION_TRANSITION_NOT_ALLOWED`을 concurrency code로 바꾸지 않는다.

## Retry와 failure mapping

선택 전략은 stale snapshot을 commit 전에 version conflict로 검출하는 방식이 아니라,
snapshot을 만들기 전에 authoritative row lock을 획득한다. 따라서 stale-write retry 횟수와
backoff는 각각 0이며 retry transaction도 만들지 않는다.

`RESERVATION_STATE_CONFLICT`는 이번 전략에서 발생 조건이 없으므로 Product error code에
추가하지 않는다. 향후 optimistic strategy를 선택하고 bounded retry가 소진된 뒤에도
authoritative current state에서 기존 business result를 확정할 수 없는 경우에만 별도 ADR과
API 계약으로 도입한다.

DB deadlock, lock wait timeout, connection failure와 transaction failure도 retry하지 않는다.
이들은 `CAPACITY_UNAVAILABLE`이나 `RESERVATION_STATE_CONFLICT`로 변환하지 않고 기존 generic
`500 INTERNAL_ERROR` system failure로 남긴다. MySQL lock wait timeout, 실제 row cycle deadlock과
allocation update failure에서 500 및 전체 rollback을 통합 테스트로 확인한다. connection
acquisition failure도 같은 generic system-failure mapping을 결정적인 handler contract test로
확인한다. commit 결과가 불명확한 mutation을 자동 replay하지 않는 기존 client 계약은 유지한다.

## 기각 이유

Optimistic capacity 후보는 correctness와 이 local run의 latency에서 유효했지만 Slot version
migration, 모든 Slot write의 version 보존, speculative Reservation/Allocation rollback과
새 transaction retry가 필요하다. Lifecycle에도 별도 Reservation version이 필요해 두
aggregate의 version/retry policy를 운영해야 한다. 현재 capacity=1 모델에서는 대상 row
lock보다 단순하지 않으므로 선택하지 않았다. 기각한 version/retry 구현은 Product에 남기지
않는다.

Tenant/Venue 전체 lock, Redis/Distributed Lock은 대상 Slot/Reservation row로 correctness를
충족하므로 도입하지 않는다.

## 결과와 재검토 조건

- same-slot HOLD는 하나의 effective consumer만 commit하고 loser는 authoritative
  `CAPACITY_UNAVAILABLE`을 받는다.
- Reservation/Allocation과 #17 reliability state의 기존 atomic commit/rollback을 유지한다.
- competing lifecycle command는 winner 뒤 current state를 다시 평가하며 stale snapshot으로
  winner를 덮어쓰지 않는다.
- #70의 confirm/expiry 최종 허용 상태와 transaction 의미론, scheduler, #17 key 계약과
  Customer retry UI는 변경하지 않는다.

실제 hotspot lock wait가 latency 목표를 반복해서 넘거나, capacity/quantity가 1보다 커지거나,
pooled capacity 또는 lifecycle contention이 도입되면 같은 workload와 추가된 Product model로
optimistic/physical allocation 전략을 다시 비교한다.

## #70 confirm/expiry 경합 재검증

검증 테스트: [ReservationConfirmExpiryIntegrationTests](../../backend/src/test/java/com/slotq/ReservationConfirmExpiryIntegrationTests.java).
#70 당시 `ReservationService`의 public 권한용 read는 command transaction 밖에서 수행하며,
confirm과 System Principal expiry는 모두 `ReservationCommandExecutor`의 첫 DB read인
`findForUpdate`를 거친다. `commandNow`는 executor transaction 진입 전에 한 번 캡처하고,
guard/domain transition/effective response에는 그 instant로 만든 fixed Clock을 사용한다.
이 경계와 Reservation → Allocation 저장 순서는 이미 계약을 만족하므로 production 변경은 없다.

MySQL 8.4의 독립된 테스트 container에서 다음 순서로 실제 두 command를 경합시킨다.

1. 외부 transaction이 Reservation row를 잠그고 선행 command의 InnoDB row-lock wait를 관측한다.
2. Reservation gate를 해제하면 선행 command가 Reservation lock을 얻는다. 테스트 전용
   `BEFORE UPDATE` trigger가 별도 gate row에서 기다리므로 winner는 아직 commit할 수 없다.
3. 이 두 번째 lock wait를 관측한 뒤 후행 command를 시작하고 세 번째 lock wait를 확인한다.
   이때 두 command 모두 미완료이고 committed pair는 여전히 `HELD + active`이다.
4. write gate를 해제해 winner를 commit시키면 loser는 current locked Reservation을 읽는다.

confirm-first는 만료 전 confirm의 `200 CONFIRMED + active`와 뒤 expiry의
`RESERVATION_TRANSITION_NOT_ALLOWED`를, expiry-first는 만료 경계 expiry의
`EXPIRED + released`와 뒤 confirm의 `409 HOLD_EXPIRED`를 검증한다. 후자의 confirm은
선행 권한 read의 committed HELD를 쓰기에 재사용하지 않는다. equality confirm도 두 순서로
경합시켜 `409 HOLD_EXPIRED`와 `EXPIRED + released`를 확인한다. equality confirm이 먼저
materialize한 경우에도 409 반환 전에 commit하며, 뒤 expiry는 same-target 성공을 유지한다.

각 worker에 별도 test-only Clock 관측값을 두고 transaction 밖에서 해당 command instant를
정확히 한 번 읽었는지 검사한다. 잠금 대기를 관측한 뒤 confirm Clock은 만료 후로, expiry
Clock은 만료 전으로 바꾸어도 최초 instant가 유지된다. 순서는 sleep 간격이나 lock queue의
FIFO 가정으로 결정하지 않으며 production synchronization hook도 추가하지 않는다.

expiry의 Allocation release에는 MySQL trigger `SIGNAL SQLSTATE '45000'` failure를 주입해
`HELD + active` 전체 rollback을 확인한다. 같은 due instant의 domain effective state는
`EXPIRED`이고 domain/기존 capacity query 모두 non-consuming이다. 모든 race와 rollback 후
DB pair 재조회, persistence/domain reconstruction, exact Reservation GET 및 read non-mutation을
검증한다.

stale retry attempt/exhaustion과 `RESERVATION_STATE_CONFLICT`는 이 선택 전략에 발생 경로가
없어 N/A이다. loser는 retry 없이 최초 transaction의 locking current read로 기존 M1 결과를
확정한다. lock timeout/deadlock/transaction failure는 기존
`ReservationTransitionIntegrationTests`, connection failure mapping은
`ProductApiErrorContractTests`의 #16 회귀 증거를 재사용한다. 이 검증은 전략 재선택이나
latency/throughput 측정이 아니다.

## #88 CONFIRM/replacement HOLD 교차 경합 보정

2026-09-11 최신 main `b0c1124dc1a97cd89e60af626cb6d4e1e9a8070f`에서 #16의 Slot lock과
#70의 Reservation lock은 각각의 race를 보호하지만 서로의 commit을 직렬화하지 않았다.
만료 전 `commandNow`를 캡처한 CONFIRM이 지연되는 동안 만료 후 replacement HOLD는
기존 stored HELD를 effective non-consuming으로 보고 commit할 수 있었다. 두 command가
모두 commit하면 같은 시각의 effective occupancy가 capacity=1을 초과한다.
`ReservationConfirmExpiryIntegrationTests.confirmReplacementHoldRaceCommitsOnlyOneCapacityWinner`
를 보정 전 코드에 실행해 두 ordering 모두 `2 > 1` 실패를 직접 재현했다.

### 직렬화와 시간/오류 계약

CONFIRM도 HOLD와 같은 Slot 한 row의 lock을 transaction의 첫 DB read로 얻는다.
권한 검증용 선행 read에서 전달하는 것은 immutable SlotInventoryId뿐이며 state, Allocation,
capacity 또는 expiry 판단을 재사용하지 않는다. command transaction은 Reservation locking
current read 뒤 locked Slot과 Reservation의 Slot/tenant/Venue/resource scope를 다시 검증한다.
Slot lock 전에 consistent read를 하지 않아 MySQL REPEATABLE-READ의 capacity snapshot이
선행 winner commit 이전에 고정되는 것을 막는다.

기존 same-target 성공, stored EXPIRED, due HELD materialization과 금지 전이 guard가 우선한다.
HELD에서 CONFIRM할 수 있을 때만 같은 scope의 기존 effective predicate에서 대상 Reservation을
제외하고 다른 consumer 존재 여부를 조회한다. 다른 consumer가 있으면 mutation 전에
`409 CAPACITY_UNAVAILABLE`로 transaction을 rollback한다. 이 조회는 consistent read이며
historical due Reservation이나 다른 consumer row를 잠그지 않는다.

`commandNow`는 #70대로 executor 진입 전에 한 번 캡처한다. lock wait 후 다시 읽지 않으며,
time guard, 다른 capacity consumer 조회, domain transition과 response에 같은 instant를 쓴다.
`commandNow < expiresAt`은 CONFIRM 시도 자격이며 capacity 확보를 보장하지 않는다.

| Slot 직렬화 winner | CONFIRM 결과 | replacement HOLD 결과 | 최종 effective units |
| --- | --- | --- | --- |
| eligible CONFIRM | `200 CONFIRMED + active` | `409 CAPACITY_UNAVAILABLE` | 1 |
| replacement HOLD | `409 CAPACITY_UNAVAILABLE` | `201 HELD + active` | 1 |

replacement가 먼저 commit한 경우 기존 Reservation은 stored HELD + active로 남을 수 있다.
만료 후에는 effective EXPIRED/non-consuming이며 loser rollback과 exact GET은 이를 materialize하지
않는다. 최초 `commandNow >= expiresAt`이면 기존 `HOLD_EXPIRED`/atomic materialization을 유지한다.
deadlock, Slot/Reservation lock timeout, connection/database/transaction failure와 commit outcome
unknown은 기존 `500 INTERNAL_ERROR`이고 business conflict 변환이나 자동 retry를 추가하지 않는다.

### Production lock order와 범위

| 경로 | lock/write 순서 |
| --- | --- |
| 새 HOLD | Slot → 선택적 idempotency claim → 새 Reservation → 새 Allocation → idempotency completion |
| 동일 HOLD key replay | Slot → idempotency claim/current row → 대상 Reservation shared current read → Allocation shared current read |
| CONFIRM | Slot → 대상 Reservation → Allocation read → state/time/capacity 판단 → Reservation/Allocation 저장 |
| 기타 lifecycle / 내부 expiry | 대상 Reservation → Allocation read → Reservation/Allocation 저장 |
| Slot 생성 | Resource → 해당 시간 범위의 Slot overlap lock → 새 Slot 저장 |
| Venue policy 변경 | Venue → 새 policy 저장 |
| idempotency cleanup | due completed idempotency row 삭제 |

기타 lifecycle/expiry의 response용 Slot 조회는 일반 consistent read이며 Slot lock을 요청하지
않는다. 기존 Reservation/Allocation update는 immutable FK identity를 변경하지 않는다.
HOLD와 CONFIRM은 Resource/Venue에 locking read 또는 update를 하지 않으므로 Slot 생성과
역순 lock 간선을 만들지 않는다. idempotency fingerprint가 다른 Slot이면 replay 전에 conflict로
끝나고 cleanup은 Reservation/Slot lock을 요청하지 않는다. Reservation → Slot lock 또는
Allocation → Reservation lock을 새로 추가하지 않는다.

추가 lock 범위는 CONFIRM 대상 Slot 한 row뿐이고, Reservation lock도 대상 한 row를 유지한다.
같은 Slot의 CONFIRM/HOLD는 직렬화하지만 다른 Slot이나 historical Reservation 전체는 잠그지
않는다. migration, version, retry framework, M3 event protocol 또는 M4 Waitlist 변경은 없다.

### MySQL 회귀 증거

기존 #70 테스트의 독립 MySQL 8.4 container, 외부 transaction과 테스트 전용 DB trigger gate를
재사용한다. CONFIRM-first는 대상 Reservation gate 뒤 CONFIRM UPDATE gate 대기를 관측한 후
replacement HOLD를 시작한다. HOLD-first는 CONFIRM의 최초 Clock capture를 latch로 보존한 채
HOLD INSERT gate 대기를 관측하고 CONFIRM transaction을 진행한다. 후행 command의 InnoDB
lock wait까지 관측한 뒤 write gate를 풀며 sleep 간격이나 lock queue FIFO를 가정하지 않는다.

두 ordering 모두 최초 pre-expiry 캡처 후 Clock을 post-expiry로 변경하고, 실제 HTTP 응답,
동일 `verificationNow`의 effective units, row 수, domain reconstruction, exact GET과 read
non-mutation을 검증한다. winner가 있는 Slot에서도 loser pair를 따로 재구성해 부분 commit이
없음을 확인한다. 추가 회귀는 Slot에서 기다리는 CONFIRM이 대상 Reservation을 선점하지 않아
expiry가 먼저 완료되고 같은 Resource의 다른 Slot HOLD도 진행됨을 검증한다.

CONFIRM write의 실제 SQLSTATE 45000 주입은 500과 HELD + active rollback을 검증한다.
`ReservationTransitionIntegrationTests`는 기존 Reservation timeout에 Slot timeout을 추가하고
기존 실제 deadlock/rollback 검증을 유지한다. 기존 #16 same-slot HOLD/due replacement,
#70 confirm/expiry/equality/expiry rollback, idempotency, authorization과 API failure 계약도
focused regression으로 함께 검증한다. 성능 수치나 M3 종료 상태를 이 보정에서 갱신하지 않는다.
