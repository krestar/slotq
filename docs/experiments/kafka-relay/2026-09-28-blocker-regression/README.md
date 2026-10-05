# PR #116 blocker 재검증 (2026-09-28)

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

## 기준과 재현

- 기준 PR head: `f76935f6869d5f6eecf549c8c11140b5357a0711`; 이 디렉터리의 raw는 blocker 수정 working tree에서 생성했다. 최종 수정 commit은 raw 생성 뒤 작성한다.
- Java 25.0.4.1, Spring Boot/Spring Kafka 4.1.1, Kafka clients 4.2.1, 실제 MySQL Testcontainers 서버 8.4.11 (`REPEATABLE-READ`).
- Kafka image: `apache/kafka:4.1.1@sha256:0bc1bb2478f45b6cea78864df86acdc11e8df2c5172477819a4d12942cbe5d40`.
- 이전 실행을 현재 revision의 fresh 실행으로 재표기하지 않는다.

Repository root에서 빈 3-node cluster를 만들고 아래 명령을 실행한다. 아래 준비 절차는 당시 빈 cluster에서 실행했다. 현재 Compose는 named persistent volume을 사용하므로 재기동만으로 topic이 비워지지 않는다. 새 실험은 격리된 fixture를 준비한다. 실행 전 `kafka-topics.sh --list` 출력이 빈 상태임을 확인했다. 스크립트가 topic을 만들고 RF=3, min ISR=2, unclean election off, 3 partitions와 ISR을 확인한다.

```powershell
docker compose -f infra/kafka/compose.fault.yml down
docker compose -f infra/kafka/compose.fault.yml up -d
docker exec slotq-kafka-fault-kafka-1-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-1:19092 --list
.\infra\kafka\verify-fault.ps1
```

`backend`에서 JDK 25 `JAVA_HOME`을 설정한 뒤, 실제 MySQL+Kafka Testcontainers 테스트를 실행한다. `KAFKA_EVIDENCE_DIR`은 새 gitignored `backend/build/reports/kafka-relay/<run-id>`의 절대 경로다.

```powershell
.\gradlew.bat test --tests 'com.slotq.events.application.KafkaRelayIntegrationTests' --tests 'com.slotq.KafkaBookingPublicationIntegrationTests' "-PkafkaEvidenceDir=$env:KAFKA_EVIDENCE_DIR" --no-daemon --console=plain
```

## 판정과 결과

1. **events dependency boundary: finding 유효.** 기존 worker가 Waitlist mapping과 JDBC 구현을 직접 참조했고, JDBC discovery/startup guard에 M4 route 문자열이 있었으며 architecture test가 integration package를 놓쳤다. 이제 integration adapter가 route/설정/wire를 소유하고 events는 두 좁은 port를 사용한다. architecture regression이 integration 및 M4 literal/concrete persistence 참조를 검사한다.
2. **3-node fresh-run evidence: finding 유효.** 기존 스크립트는 topic을 준비하지 않고 고정 probe를 사용했다. 새 run `c8040386054747be922f96a2603b9c6a`의 cluster-raw.txt는 빈 cluster에서 topic 생성, all-up ISR 3 및 one-down ISR 2의 각 current-run record 1건, two-down `NOT_ENOUGH_REPLICAS` 및 해당 current-run record 0건, 복구 ISR 3을 담는다. 이전 run record는 exact run prefix 필터를 통과할 수 없다. 같은 Docker host의 3 broker이므로 host failure 증거는 아니다.
3. **relay scheduled role: finding 유효.** `HoldIdempotencyCleanup`이 role과 무관하게 등록됐다. product role 조건을 추가했고, 실제 `@EnableScheduling` context regression에서 relay의 bean 부재/DB store 호출 0건과 product의 scheduled 호출을 확인했다. DB delivery와 Waitlist maintenance의 기존 disable guard는 유지한다.

fault-raw.json은 수정된 production relay/JDBC mapping에서 rollback, pre-relay crash, ack loss, ack 후 marking 전 crash, duplicate physical record, 두 relay claim, stale token, broker/DB pause 후 복구, retention gap 및 topic ID mismatch의 MySQL/Kafka 직접 결과다. 같은 original event의 broker offset 2개와 한 publication row가 기록된다. business-raw.json은 실제 Booking HOLD→CONFIRM→CANCEL에서 두 M4 event의 publication, DB direct DONE target, Offer 1개를 기록한다. Kafka intake는 수행하지 않았다.

SASL_SSL/ACL 코드는 이번 변경에서 수정하지 않았다. 기존 security-raw.json은 이전 revision에서 수행한 별도 검증이며 이번 current-run 증거로 재표기하지 않는다. 별도 JVM crash/rebalance matrix와 Kafka consumer intake/cutover는 #109/#108 후속 범위다.

## Backend 검증

- Architecture, runtime role/guard, relay failure classification focused regression: 성공.
- 실제 MySQL+Kafka `KafkaRelayIntegrationTests`, `KafkaBookingPublicationIntegrationTests`: 성공.
- Backend 전체 `.\gradlew.bat test --no-daemon --console=plain` 성공 (11m 58s).
- Backend `.\gradlew.bat clean build --no-daemon --console=plain` 성공 (11m 52s, 8 tasks 모두 실행). JUnit XML 기준 67 suites, 592 tests, 588 passed, 실패·오류 0, 4 skipped. 네 skip은 기존 opt-in 진단 3개와 외부 secure broker fixture가 필요한 security test 1개다.
- `git diff --check` 성공.
