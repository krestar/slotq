# #107 Kafka publication fault evidence (2026-09-28)

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

## 실행 환경과 재현

- Windows Docker Desktop, Java 25.0.4.1, Spring Boot 4.1.1, Spring Kafka 4.1.1, Kafka clients 4.2.1.
- MySQL Testcontainers `mysql:8.4`의 실제 서버 8.4.11, `REPEATABLE-READ`.
- Kafka image `apache/kafka:4.1.1@sha256:0bc1bb2478f45b6cea78864df86acdc11e8df2c5172477819a4d12942cbe5d40`.
- Testcontainers 단일 broker는 relay/DB fault 테스트에 사용했다. 별도 Compose 3-node KRaft와 SASL_SSL broker로 replication 및 ACL을 검증했다.

`backend`에서 JDK 25 `JAVA_HOME`을 설정하고 다음을 실행한다. `KAFKA_EVIDENCE_DIR`은 새 gitignored `backend/build/reports/kafka-relay/<run-id>`의 절대 경로로 지정한다.

```powershell
.\gradlew.bat test --tests 'com.slotq.events.application.KafkaRelayIntegrationTests' --tests 'com.slotq.KafkaBookingPublicationIntegrationTests' "-PkafkaEvidenceDir=$env:KAFKA_EVIDENCE_DIR"
```

fault-raw.json은 original event, publication, assignment, 독립 cursor, broker record의 직접 조회 결과다. business-raw.json은 실제 Booking HOLD→CONFIRM→CANCEL과 M4 Waitlist 경로의 원본 event, DB DONE target, Offer 수, broker record다. 각 실행은 새 Testcontainers DB/broker를 사용하므로 fixture UUID와 broker offset은 재실행 시 달라진다.

이 raw는 구현 working tree에서 위 집중 테스트를 실행해 생성했다. 생성 시 기준 main은 `16ba6b695f19988d944d43434b58b440c592cad7`이며 working tree는 dirty였다. 구현 checkpoint는 `b5d7b27`이다. 마지막 테스트 정리에서 producer factory 종료 코드를 추가했지만 publication production 코드와 raw 생성 경로는 유지했다. 당시 raw/source provenance는 Git history에 남아 있다.

## 최종 Backend 검증

- Java 25에서 `backend`에서 `.\gradlew.bat clean build --no-daemon --console=plain` 성공. JUnit XML 기준 66 suites, 588 tests, 584 passed, 0 failed/errors, 4 skipped.
- 네 skip은 기존 opt-in 진단 세 개와 외부 secure broker fixture를 요구하는 `KafkaSecurityProfileTests` 한 개다. secure profile은 위 fixture를 지정한 별도 실행에서 성공했다.
- clean build 직후 `backend`에서 `.\gradlew.bat test --no-daemon --console=plain` 성공. 동일 산출물의 `test` task는 UP-TO-DATE였다.
- relay/Booking 실제 MySQL+Kafka, migration, 인증 컬럼, SASL_SSL ACL 집중 실행도 변경 후 성공했다.

## Fault matrix

| 경계 | 실제 주입 및 검증 결과 |
| --- | --- |
| Business rollback | rollback transaction의 event ID는 `event_records`와 publication 모두 없음. |
| Relay 시작 전 crash | committed original event는 relay 실행 전 MySQL에 남고, 재개 후 별도 publication으로 발행됨. |
| Publish ack 유실 | 실제 broker send 후 ack를 relay에 전달하지 않음. 같은 publication 재claim으로 broker에 동일 original event ID의 물리 record 2개, MySQL publication 1개 PUBLISHED. |
| Ack 후 DB marking 전 crash | broker ack 이후 marking 생략, lease 만료 재claim 및 발행. 이전 fencing token marking은 0-row 거부. |
| Duplicate broker record | `fault-raw.json`의 같은 event ID가 서로 다른 offset에 존재; original event는 한 행. |
| 두 relay claim 경쟁 / stale publisher | 동시 claim 중 단일 owner 획득, token/lease 이후 stale owner의 mark/failure 거부. |
| Broker outage / restart | Testcontainers broker pause 중 retryable 실패를 기록하고 unpause 후 PUBLISHED. |
| DB outage | MySQL pause 중 cycle이 실패하고 unpause 후 원본 event와 publication이 보존된 상태로 발행. |
| Role/route/assignment startup | 실제 MySQL 위에서 `ApplicationRunner` guard를 호출해 product/relay의 incompatible role, M4 route, assignment/epoch 설정 거부; DB direct 후보도 assignment를 확인. 별도 JVM process matrix는 #109 범위. |
| Retention/log-start gap | 실제 Kafka `deleteRecords`로 log start를 ack offset 너머로 전진시킨 뒤 relay가 fail closed. topic ID 변경도 거부. |

`fault-raw.json`의 첫 번째 두 publication은 각각 두 번의 physical send 뒤 PUBLISHED이고, DB discovery cursor와 Kafka publication cursor는 독립적으로 유지된다. `business-raw.json`은 두 M4 v1 event의 original payload와 partition key를 확인하며, DB direct executor가 두 original target을 DONE 처리하고 Offer 하나를 만든 결과다. Kafka intake를 실행한 evidence는 아니다.

## Replication과 ACL

cluster-raw.txt는 [3-node Compose](../../../../infra/kafka/compose.fault.yml)의 topic describe/producer 출력이다. RF=3, min ISR=2, unclean election off에서 ISR 3 및 ISR 2일 때 `acks=all` publish가 완료되었다. 복구 후 broker log에서 두 성공 probe를 다시 읽었다. 두 broker 중단으로 ISR이 요구치 미만일 때 `NotEnoughReplicasException`이 기록되고 거부된 probe는 log에 없었다. 마지막 console consumer의 `TimeoutException`은 제한 시간 동안 추가 record가 없어 종료된 결과다. Docker Compose의 세 broker는 모두 한 host에 있으므로 host failure를 검증하지 않는다.

security-raw.json은 [secure Compose](../../../../infra/kafka/compose.secure.yml)에서 일회성 PKCS12/JAAS를 repository 밖에 둔 상태로 수행한 SASL_SSL/StandardAuthorizer 결과다. relay WRITE/DESCRIBE와 log-start 조회, Waitlist READ, monitor DESCRIBE는 허용하고, relay READ, Waitlist WRITE, anonymous SSL은 거부한다. 이 ACL 검증은 #107의 publication 권한 및 후속 intake 권한 분리를 확인하며 Kafka consumer intake 자체를 구현하지 않는다.

보안 fixture는 `infra/kafka/new-secure-fixture.ps1`, `compose.secure.yml`, `secure-acls.ps1` 순으로 생성·시작·설정한 뒤 다음을 실행한다. `KAFKA_SECURE_FIXTURE`는 생성된 임시 디렉터리 절대 경로다.

```powershell
.\gradlew.bat test --tests 'com.slotq.events.application.KafkaSecurityProfileTests' "-PsecureFixture=$env:KAFKA_SECURE_FIXTURE" "-PkafkaEvidenceDir=$env:KAFKA_EVIDENCE_DIR"
```

3-node fault profile은 `infra/kafka/compose.fault.yml`로 cluster를 시작한 뒤 `infra/kafka/verify-fault.ps1`이 failure 주입을 수행한다. #108의 durable Kafka intake, offset→DB executor handoff, consumer cutover/rollback은 여기서 실행하거나 입증하지 않았다. DEAD recovery와 retention 정리는 후속 operator 절차의 범위다.
