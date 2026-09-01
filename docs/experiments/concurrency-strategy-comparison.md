# Reservation 동시성 전략 비교

> 상태: Measured
>
> 실행일: 2026-09-02
>
> 관련 Issue: [#16](https://github.com/krestar/slotq/issues/16)

## 비교 목적

#15의 실제 Product HOLD HTTP 경로와 결과 schema를 유지하면서, 서로 다른 새 Reservation
insert가 같은 Slot capacity를 경쟁하는 경계에 optimistic version과 pessimistic row lock을
각각 적용했다. 둘 다 Reservation/Allocation/idempotency reliability state가 한 Spring/MySQL
transaction에서 commit 또는 rollback되도록 했다.

## 동일 실행 조건

| 항목 | 값 |
| --- | --- |
| 기준 revision | `33dfe554e602fbfbd6b9fa59e617981fe5fc73a9` + 후보별 dirty prototype |
| Database | `mysql:8.4`, server `8.4.11` |
| Isolation | `REPEATABLE-READ` |
| Pool | HikariCP, maximumPoolSize `10`, connectionTimeout `30000ms` |
| Workload | clients `10` × iterations `5`, 총 50 keyless HOLD |
| Seed | `15001` |
| Product model | Table × Slot, capacity `1`, Allocation unit `1` |
| partySize | `2`, seatingCapacity eligibility에만 사용 |
| HOLD/timeout | `PT5M` / `PT10S` |
| 시작 동기화 | iteration별 `CyclicBarrier` |
| 검증 | workload 직후 하나의 `verificationNow`로 모든 Slot 재조회 |

두 run은 같은 Windows 11/Docker Desktop host, Java `25.0.4.1`, Spring Boot `4.1.1`,
Gradle `9.7.1`에서 연속 실행했다. warm-up을 별도로 분리하지 않은 단일 local run이므로
latency와 throughput은 production 보장이나 일반적인 전략 우위가 아니다.

## 후보 구현

### Optimistic Slot version + bounded retry

- 임시 `capacity_version`을 Slot row에 두고 capacity 검사와 Reservation/Allocation flush 뒤
  `WHERE id = ? AND capacity_version = ?` compare-and-increment로 stale contention을 검출했다.
- 한 logical command의 `now`를 고정하고 최대 2회 transaction attempt로 제한했다.
- 첫 attempt의 loser는 Reservation, Allocation, #17 reliability row와 version 변경을 모두
  rollback한 뒤 새 transaction에서 authoritative capacity를 다시 평가했다.
- system/transaction failure는 retry하지 않았다.
- 측정 뒤 version migration, retry loop와 metric component는 Product에서 제거했다.

### Pessimistic Slot row lock

- HOLD transaction의 첫 database read를 대상 SlotInventory 한 row의
  `SELECT ... FOR UPDATE`로 만들었다.
- lock을 얻은 뒤 tenant/Venue/Resource guard, effective capacity query,
  Reservation/Allocation 저장과 #17 reliability completion을 같은 transaction에서 처리했다.
- lock을 기존 consistent read 뒤에 획득한 초기 prototype은 MySQL `REPEATABLE-READ`의 오래된
  snapshot으로 capacity query를 수행해 correctness를 깨뜨렸다. 따라서 첫 read라는 조건은
  선택 전략의 일부다.
- unrelated Slot, Venue 또는 Tenant row는 이 lock으로 직렬화하지 않는다.

## 결과

| Metric | Optimistic | Pessimistic |
| --- | ---: | ---: |
| runId | `7ed8d28d-54f1-4e93-ba11-20c59c3fded6` | `66391a9d-668d-49f6-b775-4c6f8a51186f` |
| invariant violation | 0 | 0 |
| successful HOLD | 5 | 5 |
| `CAPACITY_UNAVAILABLE` | 45 | 45 |
| system failure / timeout | 0 / 0 | 0 / 0 |
| effective occupancy / raw active | 5 / 5 | 5 / 5 |
| partial commit | 관측 없음 | 0 |
| throughput (req/s) | 90.36 | 48.95 |
| P50 (ms) | 81.14 | 127.39 |
| P95 (ms) | 202.49 | 258.64 |
| P99 (ms) | 205.80 | 298.54 |
| stale retry / exhaustion | 45 / 0 | 0 / 0 |
| system retry / exhaustion | 0 / 0 | 0 / 0 |
| MySQL row-lock wait / time | 45 / 507ms | 45 / 4484ms |
| deadlock | 0 | 0 |

Optimistic run은 5개의 committed Reservation과 5개의 active Allocation, effective occupancy
5를 남겼고 나머지 45 transaction은 stale 검출 뒤 rollback/retry하여 모두 authoritative
`CAPACITY_UNAVAILABLE`로 끝났다. Pessimistic 최종 run은 Slot별 Reservation/Allocation이
각각 1개임을 별도 집계해 partial commit 0을 확인했다.

## 해석과 선택

두 후보 모두 correctness gate를 통과했다. 이 한 run에서는 optimistic 후보가 더 높은
throughput과 낮은 tail latency/lock-wait time을 보였지만, 표본과 실행 환경만으로 일반적인
성능 결론을 내릴 수 없다.

현재 Product의 capacity=1 Slot은 정확히 하나의 기존 row가 contention boundary다.
Pessimistic 후보는 schema/version propagation, rollback되는 speculative insert와 retry
orchestration 없이 이 row 하나만 직렬화한다. correctness가 같을 때 구현과 failure model이
더 단순하다는 판단 기준에 따라 pessimistic 전략을 선택했다.

same-Reservation lifecycle도 기존 Reservation row 하나가 정확한 경계이므로 별도 version
column과 retry를 추가하지 않고 해당 row의 `FOR UPDATE` current read를 선택했다.

다음 증거가 생기면 optimistic 또는 다른 물리 모델을 다시 비교한다.

- 실제 hotspot에서 Slot lock wait가 API latency/SLO를 반복해서 넘는 경우
- capacity > 1, allocation quantity > 1 또는 pooled capacity가 Product 범위에 들어오는 경우
- lifecycle command contention이 측정 가능한 병목이 되는 경우
- 여러 writer 경로가 동일한 lock order를 유지할 수 없거나 deadlock이 관측되는 경우
