# #109 Kafka 독립 JVM / broker / DB fault evidence

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

#109에서 별도 Product/relay/consumer JVM, persistent 3-node broker와 실제 MySQL을 사용했다.
아래 표는 2026-09-29~30의 서로 다른 fault 실행을 요약한다. #107/#108 또는 현재 cleanup의
실행 결과로 재표기하지 않는다. 원본 관찰의 재계산용 `Kafka*EvidenceIntegrityTests`는 유지하며
새 fault harness가 생성한 gitignored output을 입력으로 사용한다.

## 실행 구성

- 시작 HEAD `fd3510a27627ed769a5ccad58454405067c4335a`, source/compiled class hash와
  dirty 여부는 raw에 기록했다. 고정 event seed는 `109`다.
- MySQL `8.4.11` `REPEATABLE-READ`는 run별 named volume
  `slotq-fault-109-mysql-run-20260929-h`를 쓴 독립 Testcontainers JVM이다.
- Kafka broker `4.1.1` 3-node KRaft는 `infra/kafka/compose.fault.yml`의 named volume 세 개,
  RF=3, min ISR=2, unclean leader election off, `acks=all`을 사용했다. 세 노드는 한 Docker
  host의 공통 failure domain이다. broker image digest, 실제 volume mount, ISR, leader,
  controller quorum 상태는 raw에 있다.
- 별도 JVM: Product API 1, maintenance 2, relay 2, Waitlist consumer 최대 4,
  operations observer 1. 두 logical consumer의 group은 서로 다르다. 각 PID/exit,
  실제 group assignment/offset과 runtime 설정은 raw에 기록했다.
- Test coordinator의 fault 조작과 수동 canonical wire 전송만 test source에 있다.
  Product, relay, intake, DB executor의 production protocol은 변경하지 않았다.

## 실행 명령

Java 25 / Docker가 필요하다. 새 run ID/topic/volume으로 격리한다. 기존 broker volume을 유지해야
하는 fault 중에는 `down -v`를 사용하지 않는다. Repository root에서 cluster를 준비한 뒤 `backend/`:

```powershell
docker compose -f infra/kafka/compose.fault.yml up -d --wait
Set-Location backend
$runId = 'fresh-' + [guid]::NewGuid().ToString('N')
./gradlew.bat test --tests '*KafkaFaultProcessIntegrationTests' '-PkafkaFaultBootstrap=localhost:29092,localhost:39092,localhost:49092' "-PkafkaFaultRunId=$runId" '-PkafkaFaultEvidenceDir=build/reports/kafka-fault' --no-daemon --console=plain
./gradlew.bat test --tests '*KafkaFaultEvidenceIntegrityTests' "-PkafkaFaultRunId=$runId" '-PkafkaFaultEvidenceDir=build/reports/kafka-fault' --no-daemon --console=plain
```

추가 창별 재현은 `KafkaIntakeCrashIntegrationTests`→`KafkaIntakeCrashEvidenceIntegrityTests`,
`KafkaRelayIntegrationTests`→`KafkaRelayFaultEvidenceIntegrityTests`,
`EventTransportCutoverIntegrationTests`→`KafkaCutoverFaultEvidenceIntegrityTests` 순으로 실행한다.
각 producer와 calculator에는 동일한 fresh `-PkafkaEvidenceDir=build/reports/<fresh-case>`를 지정한다.
`-PkafkaEvidenceRevision`을 기록할 때는 실제 checkout revision을 사용한다.
`backend/build.gradle`의 property forwarding, process hook, durable oracle와 Compose는 유지한다.

## 직접 확인한 fault

| Fault / 경계 | raw phase / durable oracle |
| --- | --- |
| event commit 후 relay 이전 | `before-relay`: original event 1, publication/target 0. 두 relay JVM 뒤 publication 1, 두 group target `DONE` |
| observer 독립 중단 | `observer-down-waitlist-done`: Waitlist `DONE`, observer backlog. Product API 200; observer 재시작 후 두 target `DONE` |
| Waitlist 1→4, idle member, rebalance | `one-to-four-idle-member`: group member 4, 3 partition assignment, idle 1. 한 JVM kill 후 `rebalance-converged` |
| broker leader 종료 | `before-leader-stop`→`leader-down-converged`: leader 변화, ISR ≥2, publication/intake/effect 수렴 |
| persistent broker 재시작 | `persistent-broker-restarted`: 동일 topic ID, ISR 3, volume과 KRaft voters 기록 |
| 전체 broker 중단 | `all-brokers-down`: Product event 유지, relay topic/retention probe failure. `first-broker-recovery` 이후 backlog 수렴; Product API 200 |
| 정상 기동 후 MySQL outage | `before-db-outage`→`db-unavailable-after-intake-entrypoint`: 실제 `JdbcKafkaIntakeStore.startOffset`의 JDBC exception, broker record는 존재. DB 복구/consumer 재시작 뒤 두 target `DONE` |
| 물리 중복 허용 | DB outage record와 relay 재발행이 두 group에서 각 2개 intake coordinate로 관측됐으나 original target 각 1개, Waitlist receipt 1개 |
| intake 세 crash window | `run-20260929-intake-b`의 `BEFORE_INTAKE`는 offset/target 모두 없음; `AFTER_INTAKE`는 durable target 1·offset 미진행; `AFTER_OFFSET`은 offset 진행·target `PENDING` attempts=0. Broker 재전달 없이 DB executor가 `DONE`·receipt 1로 수렴 |
| relay ACK 후 marking 전 JVM 종료 | `2026-09-30/run-20260930-intake-relay-d`: child JVM 91 종료와 ACK partition/offset, publication `PROCESSING`→lease 재claim→`PUBLISHED` attempts=2. 같은 original의 physical intake 3, target intake 1, delivery/receipt 1, business attempts=1 |
| relay ACK 응답 손실·claim 경쟁·stale mark | `run-20260929-relay-c`: 실제 MySQL ledger와 Kafka producer에서 2-way claim, ACK response loss, stale marking 거부, physical record 2개. fault phase DB snapshot과 raw 재계산 포함. 이 미시 fault는 single-broker profile이며 ACK 직후 실제 JVM 종료는 위 별도 run이 담당 |
| retention log-start gap | `run-20260929-relay-c`: broker `deleteRecords` 후 log start가 ACK offset을 초과했고 `KafkaRetentionProbe`가 incident로 거부. 자동 offset reset/recovery는 실행하지 않음 |
| quarantine persistence failure | `2026-09-30/run-20260930-quarantine-b`: 이미 기동한 production `JdbcKafkaIntakeStore.intake`에서 MySQL pause로 `CommunicationsException`이 관측됨. Broker committed next가 실패 record offset에서 멈췄고 intake row 0, 복구 뒤 같은 record를 `QUARANTINED`로 기록하고 offset을 한 칸 진행 |
| Waitlist business effect / capacity | `2026-09-30/run-20260930-j`: DB outage 전에 등록한 실제 후보가 복구 후 `PROMOTED`, receipt/Offer/Reservation/active Allocation 각각 1. Slot capacity 1, active Allocation 1. raw integrity 재계산에서 6 original 전부 설명 가능, 중복 target·partial receipt/DONE·unexplained DEAD·capacity violation 0 |
| DB executor process crash / outcome unknown | `2026-09-30/run-20260930-delivery-c`: Kafka scope target에서 wrong consumer claim 거부, claim 직후 PID 종료 후 `PROCESSING` attempts=1, effect transaction 중 종료 후 attempts=2·receipt 0, after-commit 종료 후 `DONE` attempts=3·receipt 1. 실제 M4 handler와 production `EventDeliveryWorker` 사용 |
| repeated claim crash / durable DEAD | 같은 run에서 별도 original을 3개 독립 JVM claim 뒤 종료시켜 attempts 1→2→3, 매 단계 effect/receipt 0. lease 후 재판정에서 `DEAD`·`CRASH_EXHAUSTED`, 자동 재실행 없음 |
| stale DB owner | 같은 run에서 JVM A claim token 1→lease 만료→JVM B token 2·`DONE`/receipt 1→JVM A의 지연 처리. B 완료 전후 durable target/receipt 동일 |
| transport authority mismatch / rollback | `2026-09-30/run-20260930-cutover-a`: DB_DIRECT epoch1→KAFKA epoch2→DB_DIRECT epoch3. epoch1 worker는 두 변경 뒤 모두 claim 불가, KAFKA 도중 original은 DB_DIRECT materialize 없이 남고 rollback scan 뒤 2 originals / 4 targets, attempts·receipt 0. Broker process 검증과 구분되는 MySQL authority 회귀 |
| 실제 poll timeout / old-new intake | `2026-09-30/run-20260930-q`: 300초 poll timeout 전 waiter 1, 이후 동일 `event_kafka_consumer_positions` partition에 waiter 3; group member 2→1, partition 0 owner 변경. 잠금 중 target intake 0, 해제 후 original target 1·receipt 1·두 consumer `DONE` |
| 별도 maintenance JVM admission / expiry | 같은 run에서 maintenance JVM 둘이 대기 후보의 request event 1개를 admission하고 `PROMOTED`·Offer/Reservation/active Allocation 각 1로 수렴. 첫 후보 HOLD는 만료 뒤 capacity release event 1개와 `NO_CANDIDATE`, Offer/Reservation `EXPIRED`, active Allocation 0으로 수렴 |
| caught append failure rollback | `2026-09-30/run-20260930-relay-d`: MANDATORY append의 route mismatch를 caller가 catch해도 outer business transaction rollback. event/publication/business row 모두 0 |
| retention gap의 원본 보존 | 같은 run에서 log-start gap은 `KafkaRetentionProbe` incident로 판정되고 original event와 publication row 각 1을 raw에서 확인. 자동 latest reset 없음 |
| cutover provenance | `2026-09-30/run-20260930-cutover-b`: epoch 변경/rollback 결과는 raw에서 original 2, target 4, unauthorized attempt 0으로 재계산. MySQL container와 class hash 기록 |

각 phase에는 가능한 DB original/publication/intake/target/delivery/receipt/business state와
broker offset/assignment/ISR를 함께 기록했다. DB가 멈춘 phase는 DB snapshot의 불가 상태를
명시하고, 직전과 첫 복구 시점의 durable snapshot으로 비교한다. Integrity test는 6개
original event의 최종 target을 재계산했고 unexplained loss, duplicate logical target,
partial receipt/DONE, 설명되지 않는 DEAD/quarantine, no-candidate 결과에서 예상 밖
promotional Reservation/Offer가 0이었다. `2026-09-30/run-20260930-j`는 실제
`PROMOTED` 결과와 active Allocation / Slot capacity를 추가로 검증했다.

## 검증 경계

`run-20260930-q/recalculated.json`은 raw에서 9개 original event 전부를 설명하고,
duplicate logical target·partial receipt/DONE·unexplained DEAD·capacity violation을
각각 0으로 재계산한다. Broker는 한 Docker host의 3개 persistent volume이며
broker data loss나 MySQL HA를 실험하지 않았다. Retention gap은 infrastructure incident로
판정하고 #110의 사람 operator recovery는 실행하지 않았다. 기존 #107·#108 evidence를
이번 실행의 PASS로 세지 않는다.

## 최종 로컬 검증

2026-09-30에 `backend/`에서 다음을 직접 실행했다.

```powershell
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaIntakeCrashIntegrationTests --tests com.slotq.KafkaWaitlistVerticalSliceIntegrationTests --tests com.slotq.KafkaBookingPublicationIntegrationTests --no-daemon --offline --console=plain
.\gradlew.bat test --no-daemon --offline --console=plain
```

대상 regression 3개 test가 통과했다. Backend 전체 test는 76개 suite,
610개 test 중 실패 0·오류 0·skip 9로 통과했다. Opt-in #109 process 및 raw integrity는
위 개별 명령으로 실행했고, 전체 test의 skip을 별도 PASS로 계산하지 않았다.
로컬 `clean build`는 사용자의 지시에 따라 실행하지 않았다. GitHub CI 결과는
이 문서의 검증 결과에 포함하지 않는다.
