# Waitlist durable bootstrap / activation

Issue [#96 §7](https://github.com/krestar/slotq/issues/96)의 다섯 번째 checkpoint다. 기준 HEAD
`1786a36`의 promotion effect, request admission, bounded maintenance와 #95의 target/Slot-first/
RR current-read를 변경하지 않고 실제 startup 및 scheduler wiring을 연결한다.
실제 별도 JVM 강제 종료/재시작 및 DB outage/recovery evidence는 다음 checkpoint 소유다.

## 활성화 단위와 기본값

기본값은 계속 disabled다. 같은 버전의 Product 인스턴스를 정지 후 재시작하는 초기 활성화에서
다음 세 flag를 함께 명시한다.

```properties
slotq.waitlist.promotion.enabled=true
slotq.waitlist.promotion.maintenance-enabled=true
slotq.events.delivery.scheduler-enabled=true
slotq.waitlist.promotion.maintenance-interval=PT1S
slotq.events.delivery.poll-interval=PT1S
```

enabled인데 둘 중 한 scheduler 설정이 빠지면 bootstrap 실패다. producer만 열고 실행 경로가
없는 M4 모드로 광고하지 않는다. interval은 두 scheduler 모두 **1ms 이상 1일 이하**이며 invalid
설정은 bean 생성에서 실패한다. batch/target/effect/lease/retry 설정은 기존 정책을 그대로 사용한다.

master `promotion.enabled=false`이면 bootstrap은 durable 조회/변경 없이 local gate를 닫는다.
하위 scheduler flag가 실수로 true여도 tick은 업무를 수행하지 않는다. 기존 Booking lifecycle은
그대로 동작하고 release event는 생성하지 않는다. 이미 ACTIVE인 registration의 UUID/boundary를
변경하거나 deactivate하지 않는다. 기존 M4 target/data가 삭제되거나 완료됐다는 의미는 아니다.

## Startup 순서

`WaitlistPromotionReadiness`는 처음 false인 작은 state bean이다. 같은 `isReady()`를 Booking의
`CapacityReleaseReadiness`와 foundation의 business-neutral `EventDeliveryReadiness`로 제공한다.
bootstrap과 분리해 handler → Booking → recorder → readiness의 bean dependency cycle을 만들지 않는다.

`WaitlistPromotionBootstrap`은 Spring `ApplicationRunner`이며 다음 순서로 실행한다.

1. gate를 닫고 caller transaction을 거부한다. disabled이면 DB를 건드리지 않고 종료한다.
2. enabled 설정에서 두 scheduler opt-in을 검증한다.
3. **worker가 사용하는 동일 `EventHandlers` registry**에서 두 exact route를 resolve한다.
   route와 실제 adapter target class가 `BookingCapacityReleasedHandler` /
   `WaitlistPromotionRequestedHandler`인지 검증한다. handler가 없는 상태에서 registration을 쓰지 않는다.
4. fresh fenced inspection으로 durable metadata를 검증한다. 기존 ACTIVE가 정확히 일치하면 재사용한다.
5. 누락된 최초 route만 기존 `EventRegistrationService.activate`로 생성한다. 각 activate가 commit한
   뒤 재관측한다. 한 route만 commit하고 실패한 경우 그 identity는 남지만 readiness는 false다.
6. 마지막 fresh inspection에서 두 route의 같은 ACTIVE UUID/generation을 확인한 뒤에만 gate를 연다.

| Consumer | Event type | Version |
| --- | --- | ---: |
| waitlist.promotion | booking.capacity-released | 1 |
| waitlist.promotion | waitlist.promotion-requested | 1 |

route는 대소문자를 포함해 exact-match다. 같은 expected consumer 또는 같은 두 event type의 metadata가
검사 범위다. 현재 slice 밖의 consumer/version/type이 이 범위에 있으면 compatibility 실패이며 임의
수정/제거하지 않는다. 무관한 consumer+event type의 foundation 사용에는 관여하지 않는다.
activation/deactivation boundary의 양수·순서·현재 fence 이하 여부, 관측한 boundary의 중복 사용과 같은 route의 generation overlap을
검증한다. inactive 이력만 있는 route를 startup이 새 generation으로 자동 대체하지 않는다. 유효한 과거
종료 이력과 현재 ACTIVE가 함께 있으면 현재 identity를 보존한다. runtime route 교체/제거 API는 없다.

## Race / response loss / transaction

`EventRegistrationService.inspect(consumerId,eventTypes)`는 caller transaction을 거부한다. 별도의
foundation transaction에서 `event_boundary FOR UPDATE` → 관련 registration current-read를 수행하고
commit한다. business row나 business callback을 넣지 않는다. snapshot은 boundary와 immutable
registration identity/route/interval만 제공하며 integration이 M4 compatibility를 판단한다.

기존 activate의 중복 ACTIVE 예외는 의미가 명확한 `AlreadyActiveException`으로 구별한다.
같은 fence를 쓰는 multi-instance 경쟁에서 loser는 실패한 transaction이 끝난 뒤 **새 inspect**로
정확한 ACTIVE generation이 존재함을 증명해야 한다. activation 응답 유실/transaction·DB access
예외도 새 durable 관측이 성공하고 해당 route의 ACTIVE가 확인될 때만 수렴할 수 있다. 확인 실패,
DB 장애 지속, corruption 또는 registration 부재는 실패다. 임의 programming exception은 복구했다고
삼키지 않는다. 같은 invocation 안의 무한 retry나 deactivate→activate는 없다.

등록 generation은 기존 registration UUID다. 새 epoch/schema/state table을 만들지 않는다. 재시작이
새 sequence를 할당하지 않으며 첫 activation 이전 event의 membership을 backfill하지 않는다.
기존 materialization의 `activationBoundary < eventSequence < deactivationBoundary`는 그대로다.

```text
bootstrap inspection/activation: event_boundary → registration metadata → commit
business release/request: 기존 business/admission locks → event_boundary → active route/event → commit
```

bootstrap이 fence를 가진 채 Slot/Entry/Reservation을 요청하는 역방향 edge는 없다. 기존 producer와
request append의 MANDATORY / active-route current-read fence를 변경하지 않는다. readiness cache가
true여도 route가 없거나 비활성이면 release/append 전체가 rollback한다. ApplicationRunner 완료 전
traffic window에서 release를 요청해도 recorder의 기존 fail-closed 검사가 event 없는 business-only
commit을 막는다. event를 요구하지 않는 HOLD 생성 등 기존 명령을 일괄 변경하지 않는다.

M3 claim/effect/receipt/notification/final flush/fencing/DONE/retry/replay protocol은 수정하지 않는다.
새 foundation gate는 scheduler 진입점에만 적용된다. trusted test/process fixture가 사용하는 수동
worker protocol 진입점을 activation 편의 때문에 바꾸거나 business 분기를 추가하지 않는다.

## Scheduler wiring

Spring의 `@Scheduled` task 등록은 ApplicationRunner보다 먼저 일어날 수 있다. 초기 delay나 bean
생성 순서를 correctness로 삼지 않는다.

- 기존 `EventDeliveryScheduler.tick()`은 readiness가 존재하고 true일 때만 기존 worker `runCycle()`을
  호출한다. provider가 없으면 false다. foundation은 Booking/Waitlist concrete type을 참조하지 않는다.
- `WaitlistMaintenanceScheduler.tick()`은 동일 local gate가 true일 때만 기존
  `WaitlistMaintenanceRuntime.runCycle(SystemPrincipal.INSTANCE)`을 호출한다. 네 backlog를 직접
  순회하거나 business logic/transaction을 복제하지 않는다. target/scan failures는 건수만 경고하고
  payload/SQL/customer data를 추가로 로그에 싣지 않는다.
- 두 scheduler는 gate를 여는 API, handler 직접 호출, HTTP/admin/replay endpoint, generic job queue를
  제공하지 않는다. maintenance와 delivery의 상호 순서에는 의존하지 않는다. durable state와 M3가
  다음 cycle의 입력이며 주기 사이에 일어난 response loss를 메모리 callback으로 복구하지 않는다.

## 검증 범위

`WaitlistActivationIntegrationTests`는 실제 MySQL 8.4/RR에서 production registration, readiness,
Booking producer, scheduler tick과 handler를 조합한다. fault/race 제어를 위해 자동 runner와 timer만
test fixture로 대체한다.

- 최초 activation과 component restart의 ACTIVE UUID/boundary 보존.
- 같은 최초 route를 경합하는 두 instance, typed loser의 fresh verification, route당 한 ACTIVE.
- 실제 registration commit 직후 service response loss 주입, 재발행 없이 durable identity 재확인.
- 거짓 AlreadyActive와 programming failure의 비은폐, 두 번째 registration DB trigger failure의 partial
  상태, gate 닫힘, release pair/event rollback, 이후 같은 첫 identity를 유지한 재개.
- handler 누락/consumer/version/case/구현 불일치, durable route/interval corruption 및 inactive-only 거부.
- 두 번째 registration 전 gate가 닫힌 상태의 delivery/maintenance tick과 Booking release rollback.
- disabled component restart의 durable 무변경, enabled인데 scheduler 하나 누락 시 실패.
- first registration 전 event에 target 미생성, ready cache 이후 route removal에도 append fail-closed.
- 외부 event_boundary/Slot X를 둔 실제 bootstrap/append 경쟁. bootstrap은 business record lock 없이
  fence만 기다리고 Slot X를 유지한 채 fence만 풀어도 양쪽이 완료한다. committed release target은 한 건이다.
- outer business transaction에서 bootstrap/inspection 거부.

[MySQL 잠금 원자료와 종료 oracle](../experiments/evidence/waitlist-activation-2026-09-22/LOCKS.txt)을
보존한다. bootstrap은 event_boundary만 기다리고 release는 Reservation/Allocation을 보유한 채 같은
fence를 기다렸다. Slot X를 유지한 채 fence만 풀어 양쪽이 완료되고 release target 한 건이 DONE에
도달했다. 이 capture를 implicit FK/unique/secondary-index 잠금 전체 목록이라고 주장하지 않는다.

`WaitlistProductionActivationIntegrationTests`는 runner/timer/handler를 대체하지 않는다. 실제
ApplicationRunner가 두 registration을 준비하고 **Spring 주기 실행만으로** ordinary release → 한
Offer/알림 → due maintenance → 다음 Entry 승급 → 시작 시각 WAITING/Offer/HOLD 만료가 수렴한다.
동일 joinedAt의 UUID 순서와 등록 호출 순서를 혼동하지 않도록 test clock의 등록 시각을 구별한다.

`WaitlistDisabledActivationIntegrationTests`는 실제 startup과 주기 tick에서 worker/maintenance 호출이
없고 일반 HOLD 취소는 event 없이 commit하며 기존 ACTIVE identity가 유지됨을 확인한다.
real scheduler test는 container 종료 전에 scheduler를 정지하고 진행 중 task 종료를 기다린다. 시험 종료 순서로 발생한 DB 연결 실패를
실제 outage/recovery evidence라고 주장하지 않는다.

기존 Commit 1–4 fixture는 테스트용 readiness를 @Primary로 유지하고 startup runner만 mock해 각
checkpoint의 수동 fault/registration 제어를 격리한다. production command/effect 구현은 변경하지 않는다.
실제 process crash, DB outage, mixed-version rolling upgrade 또는 운영 SLO 증거는 이 checkpoint에 없다.

## Focused 검증 결과

2026-09-22 Java 25 / Gradle 9.7.1 / MySQL 8.4.11 / REPEATABLE-READ 결과(분리 실행, 중복 제외):

| Suite | 성공 |
| --- | ---: |
| WaitlistActivationIntegrationTests | 26 |
| WaitlistProductionActivationIntegrationTests / WaitlistDisabledActivationIntegrationTests | 1 / 1 |
| WaitlistMaintenanceSchedulerTests / EventDeliveryActivationTests | 2 / 4 |
| WaitlistArchitectureTests / EventFoundationArchitectureTests | 7 / 9 |
| BookingCapacityReleaseIntegrationTests | 36 |
| WaitlistPromotionDeliveryIntegrationTests / WaitlistPromotionRequestIntegrationTests | 62 / 39 |
| WaitlistMaintenanceIntegrationTests / WaitlistRegistrationIntegrationTests | 22 / 21 |
| EventDeliveryIntegrationTests / EventDeliveryBoundaryIntegrationTests / EventAppendIntegrationTests | 19 / 11 / 15 |
| EventHandlersTests | 19 |
| ContextDependencyArchitectureTests / RepositoryPortArchitectureTests | 1 / 9 |

총 **304건 성공**, 기존 capacity-gap opt-in diagnostic 1건 제외. 실제 주기 실행의 마지막 종료 조건은
HELD/PENDING Offer/WAITING 수렴뿐 아니라 `event_records = event_deliveries = receipts`, 모든 delivery
DONE까지 확인한다. 별도 JVM 강제 종료나 DB outage는 실행하지 않았다.

초기 scheduler fixture의 synchronized mock timeout 검증은 monitor를 점유하므로 latch로 바꿨고,
실제 scheduler fixture는 container 종료 전에 scheduler를 정지한다. Spring test cache가 관리하는
context를 직접 닫지 않는다. 이 시험 문제를 정리한 뒤 해당 focused 검증은 모두 통과했다.

`backend/`에서 실행한 suite 선택:

```powershell
.\gradlew.bat test --tests '*WaitlistActivationIntegrationTests' --tests '*WaitlistProductionActivationIntegrationTests' --tests '*WaitlistDisabledActivationIntegrationTests' --tests '*WaitlistMaintenanceSchedulerTests' --tests '*EventDeliveryActivationTests' --tests '*WaitlistArchitectureTests' --tests '*EventFoundationArchitectureTests'
.\gradlew.bat test --tests '*BookingCapacityReleaseIntegrationTests' --tests '*WaitlistPromotionDeliveryIntegrationTests' --tests '*WaitlistPromotionRequestIntegrationTests' --tests '*WaitlistMaintenanceIntegrationTests' --tests '*WaitlistRegistrationIntegrationTests' --tests '*EventDeliveryIntegrationTests' --tests '*EventDeliveryBoundaryIntegrationTests' --tests '*EventAppendIntegrationTests' --tests '*EventHandlersTests' --tests '*ContextDependencyArchitectureTests' --tests '*RepositoryPortArchitectureTests'
```

전체 Backend test/clean build는 이번 checkpoint에서 실행하지 않았다. 최종 PR gate에서 사용자가
`backend/`의 `.\gradlew.bat test`, `.\gradlew.bat clean build` 실제 결과를 확인한다. 이 기록은 #96 전체
완료나 실제 process recovery 완료 선언이 아니다.
