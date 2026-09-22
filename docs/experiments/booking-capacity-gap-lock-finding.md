# #96 capacity current-read gap cycle 조사

## 판정

2026-09-21 #96 C3 갱신 이후 판정: **InnoDB physical gap deadlock의 baseline evidence**.
초기 조사에서 선언했던 Commit 2 / C3 blocker 판정은 갱신된 계약에 따라 철회한다.

최신 main의 #95 고정 target 경로에서도 동일한 합법적 application lock order 아래
read-gap → INSERT cycle이 재현된다. 새 FIFO 후보 query나 M3 consumer가 발생 조건이 아니다.
논리적으로 다른 Slot transaction도 같은 physical empty secondary-index gap을 공유할 수 있다.
단순 index 강제/FOR UPDATE/NOWAIT/Slot-only snapshot 대안은 안전한 최소 수정으로 입증되지 않았다.

이 현상을 application-controlled inverse edge와 구분한다. 전자는 M3의 effect 전체 rollback,
bounded transient retry, exhaustion→DEAD 및 trusted replay 후 단일 effect/DONE으로 처리한다.
후자는 계속 제거 대상이다. 특히 Slot→Reservation/Reservation→Slot 및
business→event_boundary/event_boundary→business의 반대 방향 대기를 허용하지 않는다.
ORM/FK/unique/secondary-index 증거를 관찰하지 않거나 모든 deadlock을 허용한다는 의미가 아니다.

이 baseline만을 이유로 #95 capacity 구조, Slot-local authority, isolation 또는 transaction
boundary를 재설계하지 않는다. 대안 확대 탐색도 Commit 2의 선행조건이 아니다.
아래 raw trace와 실패 후보는 당시 관측 그대로 보존하며 전체 C3 검증은
[Waitlist promotion architecture](../architecture/waitlist-promotion.md)의 실제 경로/회귀와 함께 판단한다.

## 기준과 재현 환경

- GitHub에서 확인한 최신 main: `0ee31ab2b641416044bec6296998e57a34442e68` (#100 squash / #95).
- 격리 worktree: `C:\Users\cell1\AppData\Local\Temp\slotq-main-capacity-20260921`.
  production 코드와 V1–V11 migration은 main 그대로이고 진단 test source 하나만 추가했다.
  Commit 1 producer, Commit 2 후보 query/handler/receipt/V12는 이 baseline에 없다.
- MySQL `8.4.11`, `REPEATABLE-READ`, Testcontainers `mysql:8.4`, Java 25 / Gradle 9.7.1.
  실제 Spring Product transaction manager, JDBC locking read, Hibernate `saveAndFlush`,
  원래 FK/unique/check 제약을 사용했다. FK check나 deadlock detection을 끄지 않았다.
- working branch는 `feat/booking-capacity-release-events`, HEAD `1296912`이며,
  진행 중이던 Commit 2 변경은 보존했다. 이번 추가 변경은 진단/회귀 테스트와 이 증거 기록이다.
- 근거 계약: [#95 §5](https://github.com/krestar/slotq/issues/95),
  [#96 C3](https://github.com/krestar/slotq/issues/96), [ADR-0006](../adr/0006-use-targeted-pessimistic-locks-for-reservation-consistency.md),
  [ADR-0007](../adr/0007-use-transactional-event-record-and-db-delivery.md).

## 최소 Product 재현

[BookingCapacityLockEvidenceTests](../../backend/src/test/java/com/slotq/BookingCapacityLockEvidenceTests.java)는
physical gap baseline을 재현하는 **opt-in 실험**이다. deadlock 발생을 C3의 성공 조건으로 만드는 일반 회귀가 아니다.

1. public fixture command로 같은 새 Tenant/Venue의 서로 다른 Resource/Slot을 만든다.
   Slot 시작 시각도 달라 Demand/Entry를 공유하지 않는다. 각각 WAITING Entry를 등록한다.
2. 두 thread가 실제 `WaitlistOfferUseCase.createTarget`을 호출한다.
3. spy는 실제 `existsEffectiveCapacityConsumerCurrent`를 실행한 **뒤에만** rendezvous한다.
   query 결과나 Slot/Entry/Offer/context/identity 조회 및 저장을 대체하지 않는다.
4. 두 read가 모두 false인 시점에 root observer가 `performance_schema.data_locks`를 수집한다.
   latch를 풀면 실제 `ReservationPersistenceAdapter.save`와 JPA flush가 실행된다.
5. 두 결과는 commit 1 / SQL error 1213 rollback 1이다. 같은 Tenant의 Reservation/Allocation/
   Offer 각각 1, 패자의 Entry WAITING을 확인한다. `SHOW ENGINE INNODB STATUS`로 victim과
   양쪽 held/requested index record를 기록한다.

```powershell
# 격리한 main에 동일한 진단 test source만 추가한 상태, backend 디렉터리
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
$env:SLOTQ_CAPACITY_LOCK_EVIDENCE='true'
.\gradlew.bat test --tests '*BookingCapacityLockEvidenceTests' --offline -g C:\Users\cell1\.gradle
```

10개 진단 case 성공: 8개 구조적 cycle/실패 후보, gap 제거 대조군 1개, RR 반례 1개.
본문과 함께 보존한 원자료는 2026-09-21 01:38 UTC run이다. test의 opt-in gate는 이후 추가했으며
재현 body는 같다. `build/capacity-lock-evidence/*.txt`로 raw lock/trace가 재생성된다.
원자료에서는 fixture 객체의 반복 출력만 제외했고 lock/SQL/victim 내용을 바꾸지 않았다.
ID는 전부 일회용 synthetic fixture 값이다.

## 실제 lock graph

### A. 최신 main의 변경 없는 #95 createTarget

[FIXED_TARGET 원자료](evidence/capacity-gap-2026-09-21/FIXED_TARGET.txt).

| Transaction | 선행 보유 | 충돌 지점 보유 | 다음 요청 |
| --- | --- | --- | --- |
| 2227 | 자기 Slot/Entry/Demand의 X record, context S, absent identity/Offer gap | `reservations.uk_reservations_scope_id`, space 28 / page 5 / heap 1, supremum **S** | 같은 gap에 JPA `INSERT reservations`의 **X INSERT_INTENTION** |
| 2228 | 다른 Slot/Entry/Demand의 X record, context S, absent identity/Offer gap | 같은 index / page / heap, supremum **S** | 같은 gap에 JPA `INSERT reservations`의 **X INSERT_INTENTION** |

`T2227 → T2228`와 `T2228 → T2227`가 동일 physical gap에서 생긴다.
MySQL은 T2228을 rollback했다. 대상 Slot PK는 서로 다르고 양쪽 모두 이미 정확한 Slot record
X lock을 보유한다. Slot-first가 이 보조 index의 비어 있는 구간까지 Slot별로 분리하지는 않는다.

### B. Waitlist/identity 조회를 제거한 capacity-only 대조

[CAPACITY_ONLY 원자료](evidence/capacity-gap-2026-09-21/CAPACITY_ONLY.txt).

같은 #95 Booking public `createHold(CreateCommand, CreateGuard)`를 호출하되 test guard는 PROCEED,
`findPromotionalCurrent`만 empty로 대체한다. Waitlist Entry/Offer/candidate SQL과 M3 SQL은 없다.
capacity query, context, Slot lock, domain HOLD 생성, JPA pair 저장은 실제 구현이다.

T2248/T2249는 `capacity_allocations.idx_capacity_allocations_effective`,
space 13 / page 6 / heap 1 supremum의 S gap을 각각 보유했다. 두 Reservation INSERT 후 실제
Allocation INSERT가 같은 gap의 X INSERT_INTENTION을 요청하여 T2249가 1213 victim이 됐다.
따라서 **새 후보 query와 무관하게 capacity query만으로 충분한 cycle**임이 입증된다.
이는 새 후보 query의 나머지 lock graph가 모두 안전하다는 주장은 아니다.

plan/데이터 배치에 따라 Reservation index 또는 Allocation index에서 먼저 부딪힌다.
index 이름 하나만 assertion하는 oracle은 불충분하다. 양쪽 모두 동일한 부재 range read → insert 구조다.

### C. #95의 별도 부재 identity / Offer 조회

- [IDENTITY_ONLY](evidence/capacity-gap-2026-09-21/IDENTITY_ONLY.txt): capacity만 false로 대체,
  실제 `findPromotionalCurrent` 유지. T2324/T2325가
  `uk_reservations_promotional_identity`의 동일 S GAP을 갖고 INSERT_INTENTION을 서로 기다린다.
- [OFFER_ONLY](evidence/capacity-gap-2026-09-21/OFFER_ONLY.txt): 실제 fixed target에서 Booking의
  identity/capacity probe만 제외. T2338/T2339가 `uk_waitlist_offers_entry`의 동일 X GAP을 갖고
  Offer INSERT_INTENTION을 서로 기다린다. X gap도 서로 호환되므로 FOR UPDATE만으로 해결되지 않는다.
- [NO_GAP_NEGATIVE_CONTROL](evidence/capacity-gap-2026-09-21/NO_GAP_NEGATIVE_CONTROL.txt):
  Booking guard/identity/capacity range read를 test에서 제외하면 실제 context/FK/unique/JPA pair
  INSERT 두 건이 commit한다. **검증을 제거한 대조군이며 production 수정안이 아니다.**

위 결과는 FK/unique 자체를 끄거나 가짜 INSERT SQL로 우회한 증거가 아니다.
현재 schema의 암묵적 잠금도 실제 실행에 포함되지만, 관측된 wait edge는 명시한 gap과
실제 INSERT_INTENTION이다. 다른 모든 FK/unique 경합에 대한 일반적인 무사고 증명은 아니다.

## 최소 수정 후보 검증

| 후보 | MySQL 결과 | 판단 |
| --- | --- | --- |
| 기존 effective Reservation composite index 강제 + STRAIGHT_JOIN | `idx_reservations_effective_occupancy`의 S gap → INSERT, 1213 | 실패; [원자료](evidence/capacity-gap-2026-09-21/FORCE_RESERVATION_INDEX.txt) |
| Allocation effective index부터 조회 | `idx_capacity_allocations_effective`의 S gap → INSERT, 1213 | 실패; [원자료](evidence/capacity-gap-2026-09-21/FORCE_ALLOCATION_INDEX.txt) |
| current read를 FOR UPDATE로 강화 | 다른 Tenant의 경계 record까지 X next-key 요청, 아래 mixed cycle로 1213 | 실패; 더 넓은 lock도 얻음 |
| FOR SHARE NOWAIT | read의 gap lock은 호환되어 둘 다 성공하고 뒤 INSERT에서 1213 | 실패; [원자료](evidence/capacity-gap-2026-09-21/CURRENT_NOWAIT.txt) |
| join의 lock을 이미 잠근 Slot에만 한정 (`FOR SHARE OF s`) | early RR snapshot 이후 commit된 HOLD를 발견하지 못함 | current-read 계약 위반 |
| nonlocking ID 탐색 후 발견한 PK만 current-read | 같은 early snapshot에서 새 Reservation ID 자체를 발견하지 못함 | current-read 계약 위반 |
| identity/Offer query와 capacity query의 상대 배치만 변경/제거 | capacity-only도 INSERT 직전 read에서 cycle | 이 두 조회의 순서만으로 제거되지 않음 |

[CURRENT_FOR_UPDATE](evidence/capacity-gap-2026-09-21/CURRENT_FOR_UPDATE.txt)의 정확한 cycle:
T2297은 경계 record 앞 X GAP을 갖고 T2296이 가진 X next-key record를 기다린다.
T2296은 그 record를 가진 채 자신의 INSERT가 T2297의 GAP에 막힌다. 후보 read 둘이 모두 끝나기도
전에 한쪽이 기다리는 형태다. 첫 실험의 “둘 다 read 완료” oracle이 이 경우 timeout했고,
그때 확인한 실제 wait/trace를 기록하도록 harness를 보정했다. 이를 해결 성공으로 숨기지 않는다.

RR 반례는 outer 일반 SELECT → 별도 ordinary HOLD commit → outer Slot FOR UPDATE → 후보 query
순으로 실행한다. snapshot ID query와 Slot-only locked join은 empty, 기존 current capacity query는
true다. Slot 획득 시점만 바꾸거나 ORM cache를 비우는 것으로 이미 생긴 RR read view는 사라지지 않는다.

InnoDB의 부재 range는 물리 index record 사이 구간이다. 서로 다른 빈 Slot 조건이 같은 gap에
들어갈 수 있으며 gap S/X는 상호 배타적이지 않다. 단순히 composite index에 Slot prefix를 넣거나
optimizer 선택을 고정하는 것만으로 모든 빈 Slot을 분리한다고 보장할 수 없다.
[MySQL 8.4 InnoDB Locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html),
[Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html).

### 현재 범위에서 채택하지 않은 방향

- speculative Reservation/Allocation INSERT 후 guard: #95의 capacity guard → pair 생성 순서와
  expected refusal의 정상 transaction 참여를 다시 설계해야 한다. 단순 flush 이동으로 적용하지 않았다.
- broad/historical scan 또는 공통 anchor 잠금: 다른 Slot/global 및 historical due row 잠금 금지와 충돌한다.
- ordinary count로 대체, M3 선행 snapshot이 없다고 가정, outer transaction 분리/REQUIRES_NEW,
  global isolation 변경: 확정된 RR/같은 physical transaction 계약을 약화하므로 기각한다.
- per-Slot current-capacity 참조/표현: existing exact Slot record를 authority의 출발점으로 삼는
  후속 검토 가치는 있지만 **선택하거나 구현하지 않았다**. Booking 소유 갱신/불변식, due history,
  replacement/CONFIRM 및 FK/flush graph를 모두 다시 검증해야 한다. 단순 active counter를 도입하며
  release 경로까지 Slot update하면 기존 Reservation-only → Slot 역순을 만들어낼 수 있다.

위는 당시 검토한 대안의 한계를 기록한 것이며 후속 설계 과제가 아니다.
최신 C3는 이 main baseline을 engine-level transient로 분류하므로 해당 query/저장 구조를 유지한다.
새 application-controlled inverse edge가 발견되면 그 별도 근거로 제거하거나 architecture finding을 보고한다.

## 독립 M3 1213 회귀

[WaitlistPromotionDeliveryIntegrationTests](../../backend/src/test/java/com/slotq/WaitlistPromotionDeliveryIntegrationTests.java)의
`independentMysql1213RollsBackWholeEffectAndBoundedRetryCommitsOnce`를 별도로 추가했다.

- 실제 production handler가 receipt/Reservation/Allocation/Offer/Entry/notification을 쓴 뒤,
  test-only InnoDB 두 row의 역순 UPDATE cycle로 실제 promotion transaction을 victim으로 만든다.
  capacity query 결함을 발생 조건으로 사용하지 않는다. 예외 mock이나 `SIGNAL 1213`도 아니다.
- 매 attempt의 `INNODB_METRICS.lock_deadlocks` 증가와 실제 deadlock trace를 확인한다.
- 첫 1213 뒤 PENDING / DB_LOCK_TRANSIENT, receipt와 새 pair/Offer/notification 0,
  Entry WAITING, DONE 미기록을 확인한다. 다음 정상 attempt는 effect 한 건과 DONE을 commit한다.
- 3회 반복 시 cycle_attempts=3 / DEAD, 자동 claim 불가와 매번 전체 rollback을 확인한다.
  trusted replay 후에는 effect 한 건만 commit한다.
- 기존 capacity-gap M3 테스트는 `diagnosticBaselineCapacityGapIsEngineTransient`로 명시하고
  opt-in으로 분리했다. deadlock 발생 자체를 일반 회귀의 성공 조건으로 사용하지 않는다.
  M3의 transient recovery correctness는 위 독립적인 실제 1213 회귀로 검증한다.

```powershell
# 현재 working branch의 backend, 독립 failure regression
.\gradlew.bat test --tests '*WaitlistPromotionDeliveryIntegrationTests.independentMysql1213RollsBackWholeEffectAndBoundedRetryCommitsOnce' --tests '*DeliveryFailureTests' --offline -g C:\Users\cell1\.gradle
```

초기 조사 검증 기록: main 격리 진단 10 cases 성공. working branch에서 독립 M3 2 cases + failure taxonomy
2 cases 성공. 실제 M3 capacity-gap 진단도 별도 성공했지만 이는 storage-engine baseline의 재현 성공이다.
전체 Backend test/clean build 또는 Commit 2의 전체 회귀를 이번 조사에서 성공했다고 주장하지 않는다.

이 finding만으로 Commit 2를 보류하지 않는다. 후속 request/maintenance/bootstrap/activation의
ownership은 변경하지 않으며 여기서 선도입하지 않는다.
