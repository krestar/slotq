# Event operations 통합 runbook

2026-10-01 fresh 통합 drill에서 아래 대표 탐지/authorized recovery/rollback flow가 PASS했다.
[원자료와 실제 timing](../experiments/m5-transport/README.md#fresh-integrated-operations-drill)을
따른다. 전체 production 배포, HA 또는 모든 recovery 상황을 검증한 runbook은 아니다.
[ADR-0008](../adr/0008-m5-event-transport-and-runtime-status.md)에 따라 기본값은 DB direct,
Kafka role은 opt-in experimental/comparison topology다.

MySQL business state와 immutable original event가 복구 source다. Kafka lag/offset은 durable
intake의 진행이고 DB delivery DONE/consumer receipt는 business 완료다. 두 inventory를 따로 확인한다.
사람의 복구 mutation은 [개인 operator runbook](operator-recovery.md)의 HTTPS, exact scope,
state/fence/authority epoch, 같은 operation ID 재조회 절차를 따른다.

## 탐지와 원인 수정

| Signal / 상황 | 먼저 확인할 durable 책임 | 원인 수정과 복구 | 미회복 escalation |
| --- | --- | --- | --- |
| `SlotqExecutionRuntimeUnavailable`, observer lag | observer group의 committed next/end와 observer DB target·projection; Waitlist DONE/receipt와 Booking 응답 별도 확인 | 해당 scope/epoch process를 복구하고 누적 intake/DB backlog의 감소 관측 | 정확한 original membership 누락, 다른 consumer의 진행 중단, restart 반복 |
| `SlotqConsumerDeadDelivery` | exact tenant/event/registration DEAD, cycle/lifetime attempts, failure code, receipt | handler/권한/호환 원인을 먼저 수정한 뒤 BUSINESS_REPLAY grant로 exact target replay; audit와 receipt/DONE 확인 | 다시 DEAD, stale fence/epoch, receipt/audit 불일치; publication 복구로 business DEAD를 우회하지 않음 |
| `SlotqKafkaLagUnavailable`, broker 중단 | 원 event, publication PENDING/PROCESSING/DEAD, DB target와 intake; broker sample unavailable은 0 lag가 아님 | broker와 권한·topic identity를 복구하고 relay/intake의 finite timeout 뒤 process 상태 확인, 필요하면 명시적 restart | topic 재생성/retention gap, PUBLISHED인데 intake 누락, broker/ACL 미복구; 승인된 quiesced rollback 판단 |
| `SlotqPublicationClaimExpired` | original event, publication lease/fence/attempt, ACK metadata와 실제 intake/receipt | unknown은 rollback으로 단정하지 않음. 기존 finite relay claim/reclaim으로 수렴 확인; DEAD 또는 확인된 retention gap만 PUBLICATION_RECOVER boundary 사용 | repeated unknown/DEAD, immutable mismatch, affected consumer 누락; offset reset/SQL update 금지 |
| `SlotqDatabaseSampleUnavailable`, consumer sample unhealthy | outage 동안 DB state는 조회 불가; outage 전 original/intake/claim inventory 보존 | 실제 MySQL 연결을 복구하고 기존 durable claim·lease·receipt에서 executor 재개. Scrape/telemetry 자체는 계속 조회 가능해야 함 | MySQL 미복구, sample 지속 stale/truncated, claim 예산 소진, receipt/DONE 불일치; DB HA/restore는 별도 범위 |

고정 10s/30s alert `for`, 60s backlog threshold는 로컬 진단 기준이다. 실제 배치의 poll,
lease, DB timeout과 scrape interval을 기록하고 신호 발생/해제, incident 시작, 원인 수정,
operator admission, 두 consumer의 durable convergence 시각을 남긴다. Alert 해제만으로 성공하지
않으며 exact original→membership→publication/intake→delivery/receipt→business outcome을 대조한다.
Sample health가 0이거나 cap/truncation이 있으면 count/age를 완전한 inventory로 사용하지 않는다.

## Quiesced 수동 Kafka → DB rollback

1. Incident와 승인된 maintenance window를 기록한다. 신규 Product writer와 maintenance를 멈추고
   Product/relay/**모든** intake·DB executor를 정지한다. Process 목록과 기존 READY epoch를 확인한다.
2. 원 event, exact registration membership, target state/attempt/lifetime/fence, receipt와 publication/
   intake inventory를 보존한다. 살아 있는 JVM이나 broker lag=0만으로 quiescence를 추정하지 않는다.
3. [Cutover 계약](../architecture/kafka-consumer.md#quiesced-maintenance-window)의 one-shot 실행을 사용한다.
   `runtime-role=cutover`, `web-application-type=none`, 모든 producer/maintenance/relay/intake/delivery
   disabled, `slotq.events.cutover.to=DB_DIRECT`를 명시한다. 같은 방향 command가 interrupted scan을
   durable cursor에서 재개한다. 임의 SQL로 assignment/epoch를 수정하지 않는다.
4. `event_transport_cutover.phase=READY`, 새 authority epoch와 정확한 assignment, missing target 0을
   확인한다. Original/target/attempt/receipt를 reset하지 않았는지 대조한다. 미완료면 runtime을 시작하지 않는다.
5. Product DB direct와 consumer-scoped DB observer를 새 epoch로 시작한다. Waitlist DB executor도
   자기 scope로만 실행한다. Original backlog와 신규 public command의 두 receipt/DONE을 확인한다.
6. 재전환이 필요하면 다시 전체를 quiesce하고 `to=KAFKA`로 같은 fence/scan/READY 절차를 수행한다.
   Topic identity·retention/authorization과 consumer routes를 먼저 확인한다. 자동 fallback 또는
   mixed-version 무중단 cutover는 지원하지 않는다.

현재 retention guard는 완료된 target의 PUBLISHED ack offset도 확인한다. Broker retention 전진은
relay를 fail closed할 수 있으며 #110 publication recovery만으로 expired consumer prefix/group을
복구하지 않는다. MySQL original/target/receipt가 보존된 상태에서 quiesced DB rollback을 판단한다.
Kafka 재전환은 topic/group/prefix admission이 가능한 경우에만 수행한다. 임의 offset/prefix reset,
SQL 수정 또는 자동 fallback으로 우회하지 않는다. 장기 Kafka adoption은 이 제약의 별도 계약과
evidence가 필요하다. DB event/receipt/audit에는 자동 TTL이 없으며 실제 보존 source와 호환 schema/
authority가 recovery horizon의 조건이다.

DB direct Product는 현재 Waitlist executor/maintenance를 함께 활성화한다. 별도 Waitlist DB consumer
replica와 observer는 같은 consumer-scoped execution guard를 사용한다. DB producer-only 배치를
검증한 것으로 해석하지 않는다. Kafka Product의 produce-only 실행과 relay/intake/executor process
분리는 shared MySQL/host 장애를 제거하지 않는다.

## 재현

`backend`의 `m5OperationsDrill`은 disposable MySQL/Kafka와 독립 JVM을 사용해 위 대표 flow를 실행한다.
Fault advice/ACK halt, synthetic Product credential, 개인 operator fixture provisioning과
maintenance 타이머 제어는 test-only다. 실제 MySQL pause 중 scoped production `runCycle`을 한 번
호출하는 probe도 test-only이며 실제 JDBC exception class/entrypoint만 보존한다. Scheduler의
redacted log를 SQL/stack trace 전문으로 바꾸지 않는다. 실제 HTTPS operator credential hash/grant/security chain,
production intake/DB executor/cutover, Prometheus alert·Grafana query·Tempo trace가 실행된다.
Secret/전체 log는 로컬 `build/`에만 남으며 성공한 canonical raw와 summary만 보존한다.

```powershell
cd backend
./gradlew.bat m5OperationsDrill '-Poutput=build/reports/m5-drill/fresh'
./gradlew.bat m5OperationsDrill '-Precalculate=build/reports/m5-drill/fresh'
```

대표 drill 및 비교의 범위/실제 결과는 [M5 evidence](../experiments/m5-transport/README.md)를 따른다.
과거 #109 PASS는 이번 실행의 PASS로 재사용하지 않는다.
