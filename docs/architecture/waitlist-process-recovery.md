# 실제 Booking / Waitlist process recovery

Issue [#96](https://github.com/krestar/slotq/issues/96)의 여섯 번째 checkpoint다. production 기준은
`eee4948d0e7063adb80bd1cd56774cc9806767da`이며 [activation](waitlist-activation.md)까지의 코드를
변경하지 않는다. 기존 M3 synthetic effect/receipt 실험과 구분되는 별도 M4 fixture다.

## 실제 경계와 test-only 제어

`waitlistProcessRecovery`는 test runtime classpath의 `WaitlistProcessRecoveryRunner`를 실행한다.
parent가 하나의 MySQL 8.4 container/database를 유지하고 새 child JVM들을 실행한다. case마다 새
tenant를 public command로 만들며 이전 case의 row를 삭제하거나 초기화하지 않는다. business clock은
각 case에서 7일씩 증가하는 일요일의 UTC 고정 시각이고 seed/phase 시각과 서버 발급 ID를 snapshot에 남긴다.

모든 child는 실제 `SlotqApplication`과 두 handler, `WaitlistPromotionBootstrap`을 실행한다.
production enabled / delivery / maintenance flag는 모두 true다. 새 JVM마다 기존 두 ACTIVE registration을
재사용하며 각 case의 before/after UUID·boundary가 동일해야 통과한다.

- release는 실제 `ReservationUseCase.transition(CANCEL)`이며 JPA business + MANDATORY append가 commit한다.
- 승급은 실제 두 handler → Waitlist public port → #95 target → receipt/notification → M3 DONE이다.
- test-only `BeanPostProcessor`는 effect fault case에서 **기존 `EventDeliveryStore`에 위임하는 proxy**만
  감싼다. actual handler와 final JPA flush 뒤 `done` 호출 직전에 transaction 안의 write를 관측한다.
- pre-commit case는 DONE SQL 전 `Runtime.halt(87)`이다. inside-effect snapshot은 write 지점 도달 진단일
  뿐 committed state가 아니다. 종료 후 parent의 별도 DB snapshot에서 partial effect 부재를 판단한다.
- post-commit case는 기존 DONE SQL에 그대로 위임하고 같은 transaction의 `afterCommit`에서
  `Runtime.halt(88)`한다. 이것은 afterCommit publish가 아니라 test-only physical commit/관측 유실 hook이다.
  faulting caller가 `process()` return을 기록하지 못해야 하며, authoritative DB에는 effect/receipt/DONE이
  모두 존재해야 한다.
- production business/transaction/retry/fencing/activation 구현은 수정하지 않았다. generic recovery state,
  synthetic consumer, compensation DELETE/UPDATE, replay 우회나 외부 provider는 없다.

test-only `ThreadPoolTaskScheduler`는 seed/fault coordination 중 timer 실행을 멈춘다. Spring이 사용하는
두 fixed-delay overload 모두 gate를 적용한다. 첫 세 single-event case의 recovery에서는 delivery timer만
진행시켜 보조 request가 섞이지 않게 fault를 격리한다. maintenance/backlog 및 DB outage recovery는 **두
실제 production scheduler를 모두** 실행한다. recovery loop가 handler나 maintenance command를 직접
반복 호출하는 구현은 아니다. batch/lease/retry 정책은 fixture 값으로 설정하며 프로토콜은 그대로다.

## 다섯 case와 DB 종료 oracle

| Case | Fault 직후 | 새 JVM의 종료 조건 |
| --- | --- | --- |
| RELEASE_CRASH | CANCELLED/Allocation inactive + release event 1, delivery 0 | Offer/receipt/notification/DONE 각 1, attempt/token 1 |
| EFFECT_CRASH | PROCESSING attempt/token 1, 원 Reservation/Allocation만 존재, Entry WAITING, effect/receipt/notification 0 | lease recovery 뒤 attempt/token 2, 최종 effect 1 |
| COMMIT_CRASH | effect/receipt/notification/DONE 각 1, caller return 없음 | 동일 event의 추가 effect 0, attempt/token 1 유지 |
| BACKLOG_SEED | due HELD 6, PENDING Offer 3, WAITING 12 중 시작 시각 지난 Entry 3 | 중간 Offer 총 12/PENDING 9, 최종 active Allocation·HELD·PENDING Offer·WAITING 0 |
| OUTAGE_SEED | 같은 backlog + outstanding request link/PROCESSING attempt 1 | 실제 DB failure 후 durable row 불변; 복구 후 같은 claim token 증가 및 backlog 수렴 |

backlog는 batch=2보다 큰 각 3개 그룹이다. 그룹당 ordinary due HOLD와 WAITING, 이전 release로 생성한
PENDING Offer와 다음 WAITING, 시작 시각이 지난 WAITING, 원래 비어 있는 Slot과 WAITING을 만든다.
recovery의 첫 business 시각은 seed +31분이다. ordinary/Offer expiry가 release를 남기고 다음 candidate가
승급하며, 원래 빈 Slot의 수요는 durable request discovery로 승급해야 한다. 이 중간 DB 상태를 보존한
후 test clock을 Slot 시작 시각으로 이동한다. 새 Offer 9개까지 만료한 마지막 backlog와 모든 delivery,
receipt, notification 대응이 수렴해야 한다. row 삭제나 임의 terminal 설정을 사용하지 않는다.

DB outage는 startup이 성공한 child의 ready gate 이후 **그 fixture MySQL만 pause**한다. 별도 gate를
풀어 실제 `EventDeliveryWorker.runCycle()`의 JDBC/Spring DB failure를 관측한 경우만 exit 90을 허용한다.
startup 실패, 단순 process kill, 관련 없는 exception 또는 정상 worker return은 성공이 아니다.
pause 동안 DB를 조회했다고 주장하지 않으며, unpause 직후 recovery 전에 다시 읽은 production row가
pause 전 row와 동일해야 한다. 이후 새 JVM의 production scheduler가 동일 request/event/claim에서 재개한다.

## Evidence와 한계

[원자료 report.json](../experiments/waitlist-recovery/report.json)이 machine-readable 판정 근거다.
각 case 폴더에는 manifest, fault 직후/중간/recovery DB snapshot과 fault 진단이 있다. 실행 중 수집한
빈 child log와 임시 gate 파일은 checkpoint에서 제외했다. log는 oracle이 아니다. parent snapshot은 UTC 세션의 별도 read-only transaction에서
동일 RR snapshot으로 관련 테이블을 읽는다.

다음 데이터를 tenant별로 함께 기록한다.

- event record / delivery 상태·cycle/lifetime attempts·fencing·lease / ACTIVE registration.
- request last-event link / 실제 promotion receipt / Reservation·Allocation / Entry·Offer / notification.
- DONE·DEAD, unfinished request, partial receipt, 중복 logical effect, 같은 시각 effective capacity 위반.
- due HOLD/WAITING, PENDING Offer, active Allocation 및 batch 초과 backlog의 중간/최종 수렴.

source revision, branch, dirty 여부, production source diff 여부, harness SHA-256, child PID/exit code,
Java/Gradle/Spring/MySQL/Docker/isolation, batch/lease/retry/interval과 business clock을 보존한다.
이번 작업 트리에서 실행한 증거를 clean revision 실험이라고 꾸미지 않는다. raw report의 dirty 값과
harness hash가 실제 실행 source를 식별하며 checkpoint commit이 해당 source와 evidence를 함께 보존한다.

parent의 monotonic `recoveryLaunchToObservationMillis`는 startup/lease recovery를 포함한다.
`seedLaunchToRecoveredObservationUpperBoundMillis`는 seed JVM 실행 전부터 DB 최종 관측까지의 넓은
window다. single-event case에서 release commit→effect 완료 시간의 상한으로만 볼 수 있으며, 실제
commandNow 차이나 정밀 commit latency가 아니다. backlog clock 이동/두 phase를 포함한 값을 처리량 또는
production benchmark/SLO로 해석하지 않는다. lock scope·1213 retry/DEAD/trusted replay의 더 세밀한 회귀는
기존 C2–C5 focused tests가 담당하며 이 다섯 case에서 그 정책을 reset하지 않는다.

### 2026-09-22 관측 결과

Java 25.0.4.1 / Gradle 9.7.1 / Spring Boot 4.1.1 / MySQL 8.4.11 / REPEATABLE-READ,
같은 MySQL과 11개 별도 child PID에서 **5/5 PASS**다. scheduler pool은 fixture에서 1이며 이 실행을
multi-instance 성능/경합 benchmark라고 주장하지 않는다.

| Case | event = receipt = DONE | Offer = notification | duplicate effect | recovery launch → DB 관측(ms) |
| --- | ---: | ---: | ---: | ---: |
| RELEASE_CRASH | 1 | 1 | 0 | 8,796 |
| EFFECT_CRASH | 1 | 1 | 0 | 8,293 |
| COMMIT_CRASH | 1 | 1 | 0 | 8,424 |
| BACKLOG_SEED | 24 | 12 | 0 | 11,728 |
| OUTAGE_SEED | 26 | 12 | 0 | 12,193 |

모든 case에서 DEAD/partial receipt/capacity violation은 0이다. 정상 no-op event도 receipt/DONE을
남기므로 event 수를 Offer 수와 같게 강제하지 않는다. request/release 실행 순서에 따라 정상 no-op 수는
달라질 수 있다. DB pause에서 실제 관측한 failure type은
`com.mysql.cj.jdbc.exceptions.CommunicationsException`이고 child exit는 90이다. 복구 후 기존 outstanding
request delivery는 attempt/token 1→2로 진행했으며, 두 backlog case의 최종 HELD/PENDING Offer/WAITING/
active Allocation은 모두 0이다. single-event seed 시작→recovered DB 관측 window는 각각
20,123 / 17,550 / 18,033ms이며 넓은 commit→effect 상한일 뿐 정밀 latency가 아니다.

## 재현

JDK 25와 Docker가 필요하다. `backend/`에서 **새 output 경로**를 지정한다. 기존 evidence 폴더는
덮어쓰지 않는다. 완료 후 fixture container는 정리되지만 각 fault/restart 구간에는 같은 DB를 사용한다.

```powershell
.\gradlew.bat waitlistProcessRecovery '-Poutput=build/reports/experiments/waitlist-recovery-new-run' --console=plain
.\gradlew.bat waitlistProcessRecovery '-Ponly=OUTAGE_SEED' '-Poutput=build/reports/experiments/waitlist-outage-new-run' --console=plain
.\gradlew.bat test --tests '*WaitlistProcessRecoveryEvidenceTests' --tests '*EventProcessRecoveryEvidenceTests'
```

첫 probe의 test timer overload 누락, parent local-time binding 및 null last-event admission mutex의
미완료 요청 오판을 보정했다. production protocol 결함으로 취급하거나 production 코드를 고치지 않았다.
타이머 gate 자체와 authoritative event/effect/notification 대응은 evidence regression test로 검증한다.

focused regression은 총 **307건 성공**, 기존 opt-in capacity-gap diagnostic 1건 제외다.
[activation checkpoint](waitlist-activation.md)의 Booking/Waitlist/M3/architecture 304건을 다시 실행했고,
`WaitlistProcessRecoveryEvidenceTests` 2건 및 기존 `EventProcessRecoveryEvidenceTests` 1건이 통과했다.
실행 source hash 검증은 Git의 LF/CRLF checkout 변환만 허용하며 내용 변경은 허용하지 않는다.
테스트 container 종료 뒤 기존 Spring context pool에서 발생한 connection 종료 경고는 위 DB pause
case의 증거로 사용하지 않는다. 실제 outage 판정은 별도 child PID의 gate, production DB 실패 및
pause 전/복구 직후/최종 DB snapshot에만 근거한다.

전체 Backend test/clean build는 최종 PR gate이며 이 checkpoint의 focused/process 실행과 구분한다.

## 2026-09-23 최종 integration gate

최신 main `0ee31ab`, 최신 #96(2026-09-21 C3 수정 포함), branch HEAD `30ac7a5`의 여섯 checkpoint를
다시 대조했다. 아래 실제 구현·MySQL 회귀·architecture/evidence에서 #96 완료를 막는 누락은 발견하지
않았다. 이는 후속 Customer/Venue flow인 #97 또는 M4 전체 Complete 선언이 아니다.

| 완료 계약 | 구현 / 검증 근거 |
| --- | --- |
| 실제 release와 두 exact route의 안전한 활성화 | `ReservationTransitionRecorder`, MANDATORY active-route append, 두 production handler, `WaitlistPromotionBootstrap`; capacity release / activation integration |
| JPA business/event 및 effect/receipt/notification/DONE 원자성 | `WaitlistPromotionService`, 기존 M3 final flush/fencing; caught failure/outer rollback/lease loss/독립 actual 1213 retry·DEAD·replay 회귀 |
| #95/#88 current capacity와 eligible FIFO, 중복·역순·no-op | post-Slot dynamic candidate와 locking current read; promotion delivery integration 및 [application/physical lock 구분](waitlist-promotion.md#application-controlled-lock-graph와-physical-lock-분류) |
| release 없는 기회와 미완료 request identity 보존 | scoped admission/link+append, receipt 완료 oracle; [request discovery](waitlist-promotion-requests.md)의 multiworker/rollback/DEAD/새 관측 회귀 |
| due/backing/next/restart 자동 수렴 | [bounded maintenance](waitlist-maintenance.md), 실제 production scheduler integration, 위 backlog>batch child-JVM case |
| 알림 요청 1회와 실제 process recovery | V12 scoped notification identity, effect rollback/중복 회귀, 위 5-case authoritative DB snapshot과 source-hash regression |

이번 gate의 변경은 `HoldIdempotencyScopeMigrationTests`, `SlotqApplicationTests`의 최신 Flyway
기대값을 V11에서 실제 최신 V14로 맞춘 것과 이 기록뿐이다. `eee4948` 이후 production source diff는
없으므로 2026-09-22의 5/5 process evidence를 재생성하지 않았다. 기존 focused **307건 성공**과
process evidence를 전체 Backend 결과로 대체하거나 이번에 process fixture를 다시 실행했다고 주장하지 않는다.

사용자의 명시적 전체 검증 요청에 따라 agent가 `backend/`에서 JDK 25.0.4.1 / Gradle 9.7.1 /
Spring Boot 4.1.1 / 실제 MySQL Testcontainers로 실행했다. 로컬 실행 옵션은
`--offline -g C:\Users\cell1\.gradle --console=plain`이며 suite filter는 사용하지 않았다.

- `.\gradlew.bat test`: **BUILD SUCCESSFUL in 8m 58s**. 51 suites, 535건 중 532건 성공,
  opt-in capacity lock diagnostic 3건 제외, failures/errors 0.
- `.\gradlew.bat clean build`: **BUILD SUCCESSFUL in 9m 1s**. 8 tasks 모두 실행,
  재컴파일·JAR 생성 및 같은 51 suites/532건 성공/진단 3건 제외, failures/errors 0.

전체 test에서 추가 실패는 없었다. container 종료 후 cached Spring context의 Hikari connection 경고는
테스트 실패나 새 DB outage evidence로 분류하지 않았다. Kafka/Redis/외부 notification provider,
generic Inbox/workflow, human recovery API 및 후속 UI는 추가하지 않았다.
