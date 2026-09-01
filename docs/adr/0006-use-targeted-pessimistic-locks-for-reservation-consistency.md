# ADR-0006: Reservation 정합성 경계에 대상 row pessimistic lock 사용

- 상태: `Accepted`
- 결정일: 2026-09-02
- 관련 Issue: [#16](https://github.com/krestar/slotq/issues/16)
- 비교 증거: [Reservation 동시성 전략 비교](../experiments/concurrency-strategy-comparison.md)

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
밖 bounded retry가 별도로 필요하다.

Pessimistic 후보는 capacity에는 SlotInventory 한 row, lifecycle에는 Reservation 한 row를
`FOR UPDATE`로 읽었다. #15와 동일한 10 clients × 5 iterations, seed `15001`, MySQL
`8.4.11`, `REPEATABLE-READ`, Hikari pool 10에서 capacity 후보를 비교했다. 두 후보 모두
invariant violation 0, successful HOLD 5, `CAPACITY_UNAVAILABLE` 45, system failure/timeout
0, deadlock 0이었다.

Optimistic은 90.36 req/s, P50/P95/P99 81.14/202.49/205.80ms, stale retry 45,
row-lock wait 45회/507ms였다. Pessimistic은 48.95 req/s,
P50/P95/P99 127.39/258.64/298.54ms, retry 0, row-lock wait 45회/4484ms였다.
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

권한 검증을 통과한 command transaction은 대상 Reservation 한 row를 첫 read에서
`PESSIMISTIC_WRITE`로 잠근다. 그 뒤 Allocation을 읽고 authoritative current state/time
guard를 평가한 다음 Reservation과 Allocation을 같은 transaction에서 저장한다. 모든
lifecycle writer는 Reservation, Allocation 순서로 접근하며 unrelated Reservation은
직렬화하지 않는다.

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
`500 INTERNAL_ERROR` system failure로 남긴다. MySQL lock wait timeout과 allocation update
failure에서 500 및 전체 rollback을 실제 통합 테스트로 확인한다. commit 결과가 불명확한
mutation을 자동 replay하지 않는 기존 client 계약은 유지한다.

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
