# #109 Kafka 독립 JVM / broker / DB fault checkpoint

이 디렉터리는 #109의 fault workstream에서 생성한 새 raw evidence다. #107·#108의 과거
결과를 이번 실행으로 재표기하지 않았다. `run-20260929-h/process-raw.json`은 production
`SlotqApplication`을 별도 JVM으로 실행한 관측이며, `recalculated.json`은 그 raw만 읽는
`KafkaFaultEvidenceIntegrityTests`가 재계산했다.
`run-20260929-intake-b/intake-crash-raw.json`은 세 독립 JVM intake crash window를
기록하며 `intake-crash-recalculated.json`은 별도 integrity test가 재계산했다.
`../2026-09-30/run-20260930-intake-relay-d`는 동일 intake window 뒤 독립 relay JVM을
실제 ACK 직후 종료하고 새 physical coordinate가 기존 logical target을 재사용하는지를 검증한다.
`../2026-09-30/run-20260930-quarantine-b`는 MySQL pause 중 poison record의
quarantine persistence 실패와 offset 정지, 동일 record의 복구 후 durable 판정을 기록한다.
`../2026-09-30/run-20260930-j`는 별도 JVM·3-node broker·MySQL outage 뒤 실제
Waitlist 후보가 `PROMOTED`로 수렴한 run이다.
`../2026-09-30/run-20260930-delivery-b`는 Kafka scope의 DB executor가 실제
자식 JVM 종료 뒤 lease/fencing/receipt/DONE과 bounded `DEAD`를 유지하는지 기록한다.
`run-20260929-relay-c`는 single-broker relay 미시 fault의 MySQL/Kafka snapshot이다.

## 실행 구성

- 시작 HEAD `fd3510a27627ed769a5ccad58454405067c4335a`, source/compiled class hash와
  dirty 여부는 raw에 기록했다. 고정 event seed는 `109`다.
- MySQL `8.4.11` `REPEATABLE-READ`는 run별 named volume
  `slotq-fault-109-mysql-run-20260929-h`를 쓴 독립 Testcontainers JVM이다.
- Kafka broker `4.1.1` 3-node KRaft는 `infra/kafka/compose.fault.yml`의 named volume 세 개,
  RF=3, min ISR=2, unclean leader election off, `acks=all`을 사용했다. 세 노드는 한 Docker
  host의 공통 failure domain이다. broker image digest, 실제 volume mount, ISR, leader,
  controller quorum 상태는 raw에 있다.
- 별도 JVM: Product API·maintenance 1, relay 2, Waitlist consumer 최대 4,
  operations observer 1. 두 logical consumer의 group은 서로 다르다. 각 PID/exit,
  실제 group assignment/offset과 runtime 설정은 raw에 기록했다.
- Test coordinator의 fault 조작과 수동 canonical wire 전송만 test source에 있다.
  Product, relay, intake, DB executor의 production protocol은 변경하지 않았다.

## 실행 명령

Java 25, Docker Desktop 상태에서 repository root와 `backend/` 기준:

```powershell
docker compose -f infra/kafka/compose.fault.yml up -d --wait
Set-Location backend
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaFaultProcessIntegrationTests -PkafkaFaultBootstrap=localhost:29092,localhost:39092,localhost:49092 -PkafkaFaultRunId=run-20260929-h -PkafkaFaultEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-29 --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaFaultEvidenceIntegrityTests -PkafkaFaultRunId=run-20260929-h -PkafkaFaultEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-29 --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaIntakeCrashIntegrationTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-29/run-20260929-intake-b -PkafkaEvidenceRevision=888233aaa93f6947b141d4786edffdf7c7a83826 --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaIntakeCrashEvidenceIntegrityTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-29/run-20260929-intake-b --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.application.KafkaRelayIntegrationTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-29/run-20260929-relay-c --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.application.KafkaRelayFaultEvidenceIntegrityTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-29/run-20260929-relay-c --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaIntakeCrashIntegrationTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-30/run-20260930-intake-relay-d --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaIntakeCrashEvidenceIntegrityTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-30/run-20260930-intake-relay-d --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaIntakeCrashIntegrationTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-30/run-20260930-quarantine-b --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaIntakeCrashEvidenceIntegrityTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-30/run-20260930-quarantine-b --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaFaultProcessIntegrationTests -PkafkaFaultBootstrap=localhost:29092,localhost:39092,localhost:49092 -PkafkaFaultRunId=run-20260930-j -PkafkaFaultEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-30 --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaFaultEvidenceIntegrityTests -PkafkaFaultRunId=run-20260930-j -PkafkaFaultEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-30 --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaIntakeCrashIntegrationTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-30/run-20260930-delivery-b --no-daemon --offline --console=plain
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaIntakeCrashEvidenceIntegrityTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-fault/2026-09-30/run-20260930-delivery-b --no-daemon --offline --console=plain
```

같은 run ID를 재사용하면 이미 생성된 Kafka topic과 MySQL volume 때문에 실패한다.
새 실험에는 새 run ID를 지정해야 한다. Docker Compose를 재기동할 때 `down -v`를
사용하지 않아야 broker volume이 보존된다.

## 이 checkpoint에서 직접 확인한 fault

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
| DB executor process crash / outcome unknown | `2026-09-30/run-20260930-delivery-b`: Kafka scope target에서 wrong consumer claim 거부, claim 직후 PID 종료 후 `PROCESSING` attempts=1, effect transaction 중 종료 후 attempts=2·receipt 0, after-commit 종료 후 `DONE` attempts=3·receipt 1. 실제 M4 handler와 production `EventDeliveryWorker` 사용 |
| repeated claim crash / durable DEAD | 같은 run에서 별도 original을 3개 독립 JVM claim 뒤 종료시켜 attempts 1→2→3, 매 단계 effect/receipt 0. lease 후 재판정에서 `DEAD`·`CRASH_EXHAUSTED`, 자동 재실행 없음 |

각 phase에는 가능한 DB original/publication/intake/target/delivery/receipt/business state와
broker offset/assignment/ISR를 함께 기록했다. DB가 멈춘 phase는 DB snapshot의 불가 상태를
명시하고, 직전과 첫 복구 시점의 durable snapshot으로 비교한다. Integrity test는 6개
original event의 최종 target을 재계산했고 unexplained loss, duplicate logical target,
partial receipt/DONE, 설명되지 않는 DEAD/quarantine, no-candidate 결과에서 예상 밖
promotional Reservation/Offer가 0이었다. `2026-09-30/run-20260930-j`는 실제
`PROMOTED` 결과와 active Allocation / Slot capacity를 추가로 검증했다.

Raw SHA-256: `ec7eb78a2fccb12d9417e102516e8ebc29e965458e52ec3a11a796e3889f4913`
(`process-raw.json`; `recalculated.json`의 `rawSha256`과 동일).
2026-09-30 candidate run raw SHA-256은
`7d5149feb413b09437dff4b942d4ab5e2f89f60b1ee74745e9872a6817535f94`다.

## 아직 별도 검증이 필요한 #109 matrix

이 checkpoint는 #109 완료 판정이 아니다. slow intake의 실제 poll timeout과
old/new member의 동일 record 관측, stale DB owner, transport authority mismatch / rollback,
전체 Backend test/clean build를
후속 workstream에서 추가한다. 기존 #107·#108 evidence를 이 항목의 새 PASS로 세지 않는다.
