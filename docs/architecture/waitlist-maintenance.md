# Waitlist bounded maintenance

Issue [#96 §6](https://github.com/krestar/slotq/issues/96)의 네 번째 checkpoint다. 기준 HEAD는
`ed1d72f`이며 [promotion effect](waitlist-promotion.md)와
[request admission/discovery](waitlist-promotion-requests.md)의 계약은 변경하지 않는다.
durable registration/bootstrap, scheduler 활성화와 실제 JVM crash/DB outage 실험은 후속 checkpoint다.

## 실행 / scan 경계

`WaitlistMaintenanceRuntime.runCycle(SystemPrincipal)`은 명시적으로 호출하는 한 cycle이다.
HTTP entry point, 자체 thread, scheduler 또는 delivery 실행은 없다. caller transaction은 거부하며
동일 component의 cycle 중첩만 직렬화한다. 서로 다른 component/인스턴스의 correctness는 DB target
경계가 보장한다. process-local synchronized는 cluster lock이나 business authority가 아니다.

| Backlog scan | Candidate 조건 | 개별 실행 |
| --- | --- | --- |
| Booking 소유 HELD ID page | stored expiresAt <= scanNow | 기존 `ReservationExpiryUseCase.expire` |
| Waitlist 소유 PENDING Offer ID page | 모든 PENDING, 기한 전도 포함 | `WaitlistOfferMaintenanceUseCase` → 기존 #95 reconcile executor |
| Waitlist 소유 WAITING Entry ID page + immutable Demand | startsAt <= scanNow | 새 `WaitlistEntryExpiryUseCase.expireWaiting` |
| 기존 available Slot page | admission에서 현재 matching WAITING 재관측 | 기존 `WaitlistPromotionDiscovery.discover` |

앞의 세 scan은 각각 별도 짧은 read-only transaction에서 끝낸다. `state = ? AND id > cursor
ORDER BY id LIMIT batch`로 DB BINARY(16) 순서를 그대로 사용한다. HELD/WAITING은 **미래 row도
batch/cursor에 포함**하고 그 중 due target만 실행한다. 따라서 미래/NOT_DUE prefix를 반복해서
읽느라 뒤 due row를 굶기지 않는다. WAITING은 bounded Entry derived table 뒤 immutable Demand를
join한다. queue 전체를 읽거나 잠그지 않고 scan에서 authoritative 상태를 결정하지 않는다.

V14는 세 backlog의 `(state,id)` nonunique index만 추가한다. 기존 capacity index/query/authority,
unique/FK/state 의미는 그대로다. pagination SQL의 반환/검토 row 수와 실행 시간은 제한하지만
optimizer의 모든 물리 record 접근 수에 대한 hard cap이라고 주장하지 않는다.

각 backlog에 독립적인 메모리 cursor를 둔다. full page면 마지막 ID 다음으로, 짧은 page면 null로
돌아가 다음 sweep을 시작한다. target 성공/no-op/실패와 관계없이 scan한 page를 지난다. scan 자체가
실패하면 그 cursor는 유지하고 다른 종류의 backlog를 계속 처리한다. 새 row가 cursor 앞에 생겨도
다음 sweep에서 발견한다. component restart는 cursor를 버리고 null부터 재관측한다. cursor는 완료
ledger가 아니며 durable Reservation/Offer/Entry/request/receipt가 복구 입력이다.

## Transaction / commandNow / lock

각 due target은 같은 Product transaction manager의 새 단일 target transaction에서 기존 command를
호출한다. 기존 executor의 REQUIRED가 참여하고, 한 target 반환 뒤 outer commit까지 끝내고 다음
target으로 간다. REQUIRES_NEW, transaction suspension, 여러 target의 묶음 commit은 없다.
HOLD/Entry target timeout은 coordinator의 TransactionTemplate에 적용한다. Offer는 Waitlist 소유의
좁은 maintenance adapter가 routing read-only transaction을 먼저 끝내고 기존 #95 executor만 별도
제한시간 target transaction으로 호출한다. target 전 routing은 mutation/effect의 별도 commit이 아니다.
Booking/System 및 #95의 commandNow
캡처는 수정하지 않는다. WAITING expiry는 target 진입 시 clock을 한 번 읽고 Entry lock 전에 고정한다.
cycle scanNow는 후보 필터에만 사용하며 mutation으로 전달하지 않는다. lock 대기 후 재캡처도 없다.

| Target | Application-controlled lock 방향 |
| --- | --- |
| ordinary/promotional stored HOLD expiry | 기존 Reservation/Allocation 변경·flush → 실제 release recorder → event_boundary/active route/event |
| Offer reconcile | 기존 #95 Entry/Demand/Offer/backing 경계 및 terminal persist; 새 Slot 잠금 없음 |
| WAITING expiry | scoped Entry primary current lock → 같은 Entry state/active-membership/index 갱신 |
| request discovery | 기존 별도 scan/admission transaction; admission → receipt → fresh advisory read → link → append |

WAITING expiry는 `FOR UPDATE OF entry`로 Demand를 잠그지 않는다. Demand의 scope/time은 immutable이고
state는 current locking read다. 현재 WAITING이면서 captured commandNow에 시작한 경우에만 domain
`expireWaiting`이 EXPIRED로 전이한다. 이미 OFFERED/terminal이거나 아직 미래면 false이며 Offer/HOLD,
receipt, notification, release event는 만들지 않는다. wrong scope/missing target은 오류다.

초기 구현에서 coordinator가 #95 public service의 routing read까지 writable target으로 감싼 경우,
multiworker가 이미 EXPIRED로 수렴시킨 Offer를 current lock으로 얻고도 terminal backing 검증은 그 전
RR snapshot의 HELD/active를 읽어 `EXPIRED Offer backing is not released`로 실패했다. 이를 no-op나
engine deadlock으로 분류하지 않는다. `WaitlistOfferMaintenanceService`는 기존 service와 같은
**routing-before-target** 배치를 제한시간과 함께 보존한다. #95 executor/Customer API/Booking capacity
또는 Commit 2 effect를 고치지 않는다. terminal Offer만 있고 backing release가 실제 없는 corruption
거부도 그대로다. 결정적 routing/reconcile 경쟁과 multiworker 회귀로 이 integration 보정을 검증한다.

ordinary expiry와 reconcile은 같은 backing의 actual Allocation active→inactive recorder를 재사용한다.
먼저 commit한 release만 append하며 후속 reconcile은 합법 terminal 상태로 수렴한다. expire/reconcile
뒤 같은 transaction에서 다음 candidate를 승급하지 않는다. release/request는 기존 M3 effect가 처리한다.

새 event_boundary→business 또는 Reservation→Slot edge를 만들지 않는다. 기존 #95/M3 query와
transaction 및 RR current-read는 변경하지 않는다. V14 secondary index의 write lock까지 MySQL에서
관찰한다. 수정된 C3에 따라 동일 합법 순서의 engine gap/next-key/insert-intention 1213과 application
inverse edge를 구별한다. 기존 독립 actual 1213 전체 effect rollback/retry/DEAD/replay 회귀와 opt-in
capacity-gap 진단은 그대로 유지한다. public expiry에 M3 retry 정책을 역으로 도입하지 않는다.

## 실패 / bounds / 활성화

- `maintenance-enabled=false`가 기본이다. 이것과 `promotion.enabled`가 모두 true여야 동작하며,
  기존 integration readiness가 없거나 false면 mutation 전에 실패한다. provider/registration은 추가하지 않는다.
- `maintenance-batch-size`: 1–1000, 기본 32. 각 HELD/Offer/Entry page의 상한이다.
- `maintenance-timeout-seconds`: 1–30, 기본 5. 각 scan/target transaction에 적용한다.
- request discovery는 기존 discovery-batch-size/request-timeout-seconds를 그대로 사용한다.
  cycle의 scan row/target command 상한은 `3 * maintenance-batch-size + discovery-batch-size`다.
- `Cycle`의 숫자는 scan row 수이며 mutation/commit 성공 수가 아니다. `Failure(kind,targetId,cause)`는
  원 실패를 보존한다. null targetId는 scan/discovery 호출 실패다. business exception과 infra exception을
  정상 no-op로 숨기거나 unknown commit을 확정 rollback이라고 판정하지 않는다.
- 동일 cycle에서 재시도하지 않는다. 이후 sweep의 durable 재관측은 허용하되 내부 무한 retry,
  raw row 삭제, 임의 terminal 보상은 없다. request의 DEAD/미완료 identity를 새 ID로 우회하지 않는다.
- 계속 실행되는 runtime, 복구된 DB 및 유한 workload 조건에서 진행성을 보장한다. 영구 오류 자체의
  성공이나 장애 중 deadline은 보장하지 않는다. scheduling interval/production 실행 계기는 후속 활성화 소유다.

## 검증

`WaitlistMaintenanceIntegrationTests`는 MySQL 8.4/RR에서 다음을 검사한다.

- ordinary HELD / promotional PENDING / WAITING 각 backlog > batch, component 교체 후 수렴.
- ordinary confirm/cancel로 변경된 기한 전 backing의 ACCEPTED/DECLINED 수렴.
- ordinary expiry와 Offer reconcile의 실제 Reservation lock 경쟁, Allocation/event 1회 및 EXPIRED 수렴.
  외부 Slot X lock은 양 target이 완료할 때까지 유지한다.
- 두 독립 runtime이 같은 backlog를 순회해도 terminal/release 중복 없음.
- 실제 append trigger failure의 Reservation/Allocation/event 전체 rollback, 실패 prefix 뒤 진행 및 재관측 복구.
- scanNow와 commandNow 분리, 다음 target에서 시각 재캡처, lock wait 뒤 occurredAt 고정.
- Entry UPDATE 후 performance_schema record lock이 Entry table에만 한정되고 다른 Entry도 진행.
  원 관측은 `build/waitlist-maintenance-entry-locks.txt`로 생성한다.
- ordinary expiry/Offer reconcile의 JPA flush 후 event_boundary 대기에서 Reservation/Allocation 및
  기존 #95 Entry/Demand/Offer lock을 관측한다. Slot X를 별도로 유지해도 fence만 해제하면 완료한다.
- routing snapshot 뒤 다른 worker의 terminal commit을 결정적으로 교차하고, 기존 projection
  corruption guard 및 새 bounded reconcile adapter의 전체 append rollback을 검증한다.
- early RR snapshot 이후 다른 transaction의 cancel을 current-read로 인식, OFFERED 무변경,
  scope 거부 및 outer rollback.
- NOT_DUE Offer prefix 뒤 진행, scan 실패의 다른 종류 영향 차단, stale terminal Reservation 재검증,
  실제 target lock timeout 뒤 다음 target 진행.
- release 없는 기회는 request만 생성하고 실제 M3가 HOLD 생성. due Offer→release→M3→다음 Entry 승급.
- disabled/readiness/outer transaction guard와 positive finite configuration.

이 결과는 component 재생성 검증이지 JVM crash/DB outage 증거가 아니다. 전체 #96 완료로 표시하지 않는다.
전체 Backend test/clean build는 사용자 최종 PR gate이며 이 checkpoint에서 실행하지 않는다.

2026-09-22 Java 25 / Gradle 9.7.1 / MySQL 8.4.11 / RR focused 결과(분리 실행, 중복 제외):

| Suite | 성공 |
| --- | ---: |
| WaitlistMaintenanceIntegrationTests | 22 |
| WaitlistEntryTests | 3 |
| WaitlistArchitectureTests / RepositoryPortArchitectureTests / ContextDependencyArchitectureTests | 7 / 9 / 1 |
| WaitlistPromotionRequestIntegrationTests | 39 |
| WaitlistPromotionDeliveryIntegrationTests | 62 |
| EventDeliveryIntegrationTests | 19 |
| WaitlistRegistrationIntegrationTests | 21 |
| BookingCapacityReleaseIntegrationTests | 36 |
| EventFoundationArchitectureTests | 9 |

총 228건 성공, 기존 capacity-gap opt-in 진단 1건 제외. 위 routing snapshot integration 실패를
최소 경계 보정 후 재검증했다. maintenance에서는 1213이 관측되지 않았으며 기존 독립 actual 1213
M3 rollback/retry/DEAD/replay 회귀가 통과했다.
[잠금 원자료와 종료 oracle](../experiments/evidence/waitlist-maintenance-2026-09-22/LOCKS.txt)을 보존한다.
performance_schema에 보이는 explicit record lock은 implicit FK/secondary-index lock 전체 목록이라는
뜻이 아니다. 기존 constraint와 V14를 적용한 실제 mutation/경쟁/rollback 종료 상태를 함께 검증했다.

```powershell
.\gradlew.bat test --tests '*WaitlistMaintenanceIntegrationTests' --tests '*WaitlistEntryTests' --tests '*WaitlistArchitectureTests' --tests '*RepositoryPortArchitectureTests' --tests '*ContextDependencyArchitectureTests'
.\gradlew.bat test --tests '*WaitlistPromotionRequestIntegrationTests' --tests '*WaitlistPromotionDeliveryIntegrationTests' --tests '*EventDeliveryIntegrationTests' --tests '*WaitlistRegistrationIntegrationTests' --tests '*BookingCapacityReleaseIntegrationTests' --tests '*EventFoundationArchitectureTests'
```
