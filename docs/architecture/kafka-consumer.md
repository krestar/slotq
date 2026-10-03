# M5 Kafka consumer와 transport 전환 (#108)

최종 지위는 [ADR-0008](../adr/0008-m5-event-transport-and-runtime-status.md)을 따른다.
DB direct가 supported default이며 이 Kafka 경로는 reproducible experimental/comparison topology다.
아래 구현 계약은 유지하지만 상시 운영 supported alternative 채택을 의미하지 않는다.

이 문서는 [#108](https://github.com/krestar/slotq/issues/108)의 첫 production 경계다. MySQL
`event_records`가 원본이고 `event_transport_assignments`의 registration별 transport/epoch가
실행 권한을 정한다. Kafka offset은 business 완료가 아니라 `event_deliveries` target과
`event_kafka_intake_records`의 MySQL commit으로 인계된 **partition별 연속 intake prefix**다.

## 두 logical consumer

| Logical consumer | Kafka group | Effect |
| --- | --- | --- |
| `waitlist.promotion` | `slotq.waitlist.promotion.v1` | 기존 M4 promotion receipt, HOLD/Offer, DONE |
| `operations.event-observation` | `slotq.operations.event-observation.v1` | 제한된 projection, DONE |

두 consumer는 `booking.capacity-released` v1과 `waitlist.promotion-requested` v1을 각각
자기 group/process에서 받는다. registration UUID는 원 target generation이며 group이나
process/replica identity가 아니다. 현재 handler 등록 여부가 과거 event의 membership을
다시 결정하지 않는다.

Intake는 원 MySQL event와 #107 wire의 immutable identity/meaning을 대조하고 original
registration interval 및 assignment/epoch를 검사한다. 한 MySQL transaction이 target,
첫 target intake 시각, broker coordinate 결정, partition prefix를 commit한다. 그 뒤에만
Kafka group offset을 수동 commit한다. 중복 record의 target은 재사용한다. 합법 non-target은
근거 코드를 남기고, malformed/unknown/canonical corruption은 payload 없는 transport
quarantine으로 보존한다. Quarantine 저장 실패 시 offset을 진행하지 않는다.

DB executor는 선택된 logical consumer, transport, epoch의 target만 claim/execute/replay한다.
`event_discovery`는 모든 consumer를 위한 공통 DB direct discovery cursor다. Kafka가
만든 PENDING target은 broker redelivery 없이도 DB scheduler가 찾아 첫 attempt를 시작한다.
Kafka consumer poll은 동기·유한 batch이며 business retry는 기존 MySQL delivery policy만
사용한다. Kafka partition 순서는 M4 FIFO/capacity 근거가 아니다.

#111의 실제 two-consumer RR 측정에서 cold target의 absent-row `FOR UPDATE` 후 UPSERT가
gap-lock deadlock을 일으켰다. Intake는 locked partition prefix의 **새 next coordinate**를
strict INSERT로 기록하며, 재전달된 기존 coordinate만 locking current read로 검증한다.
Target도 strict INSERT를 우선하고 duplicate key일 때 정확한 tenant/event/registration row를
locking current read로 확인한다. 잘못된 unique target 충돌은 rollback한다. Original membership과
assignment의 current lock, prefix/coordinate/canonical 검증, first materialization 의미 및
target state/attempt/fence 불변은 유지한다. 별도 자동 intake retry나 transport fallback은 추가하지 않는다.

Observer projection은 자기 effect transaction에서 DONE과 함께 commit한다. 저장하는 것은
원 event reference/type/schema, intake와 projection 시각, v1 transition 또는 promotion
request activity다. 별도 release reason이나 고객 알림 성공을 추론하지 않는다. 관리 조회는
Owner/Manager의 venue/tenant 권한과 최대 31일·100건 범위로 제한한다.

## Quiesced maintenance window

1. 호환 version을 DB direct 상태에서 준비하고 두 consumer의 정확한 original registration을
   활성화한다. Product, maintenance, DB executor, relay, Kafka consumer를 모두 정지한다.
2. `runtime-role=cutover`, `spring.main.web-application-type=none`, 모든 scheduler/consumer
   disabled 상태에서 `slotq.events.cutover.to=KAFKA` 또는 `DB_DIRECT` one-shot command를
   실행한다. `event_boundary` fence와 assignment/epoch 전환은 짧은 metadata transaction이다.
3. 같은 command가 fence 이전 원 event/registration을 bounded batch로 scan해 누락 target을
   멱등 복원한다. 중단되면 동일 방향 command가 durable cursor에서 재개한다. `READY`와
   missing target 0을 확인하기 전 어떤 Product/relay/consumer role도 시작하지 않는다.
4. 전환 후 모든 role을 새 epoch 설정으로 시작한다. rollback도 같은 정지·fence·inventory·scan
   순서를 역방향으로 수행한다. broker 장애 시 자동 DB fallback은 없다.

`event_transport_cutover`는 마지막 방향, epoch, fence, scan cursor, READY 상태를 보존한다.
PENDING/PROCESSING/DEAD/DONE, attempts, receipt는 리셋하지 않는다. 이전 epoch worker의
direct claim/effect/replay는 durable assignment 검사에서 거부된다. 기존 shared discovery
cursor가 Kafka 기간의 event를 지나갔어도 rollback scan이 원 target을 복원한다.

## Runtime 설정

모든 Kafka role은 #107의 topic/security 설정을 공유한다. `slotq.events.delivery.authority-epoch`
값은 cutover inventory의 epoch와 같아야 한다.

| Role | 주요 설정 |
| --- | --- |
| Product DB direct | `runtime-role=product`, Waitlist promotion/maintenance/delivery scheduler enabled, `delivery.transport=DB_DIRECT` |
| Product Kafka | `runtime-role=product`, `kafka.business-enabled=true`, Waitlist promotion/maintenance enabled, DB delivery scheduler disabled |
| Relay | `runtime-role=relay`, `kafka.relay-enabled=true`, web none, Product/DB schedulers disabled |
| Waitlist Kafka | `runtime-role=consumer`, `delivery.consumer-id=waitlist.promotion`, `delivery.transport=KAFKA`, `kafka.consumer-enabled=true`, Waitlist promotion + DB delivery scheduler enabled, maintenance disabled, web none |
| Observer Kafka | `runtime-role=consumer`, `delivery.consumer-id=operations.event-observation`, `delivery.transport=KAFKA`, `kafka.consumer-enabled=true`, observer + DB delivery scheduler enabled, Waitlist promotion disabled, web none |

DB direct observer 비교 mode는 같은 observer consumer role을 쓰되 `delivery.transport=DB_DIRECT`,
Kafka intake disabled로 둔다. Product는 observer registration 준비를 위한 DB direct bootstrap을
수행할 수 있지만, observer target을 claim하지 않는다.

Scrape가 필요한 isolated relay/consumer는 #111에서 `web=servlet`과
`slotq.events.management-only=true`를 함께 명시할 수 있다. TLS 및 별도 machine bearer로
보호된 `GET /actuator/prometheus`만 노출하며 Product/recovery/diagnostic HTTP는 차단한다.
Product/cutover에 management-only를 적용하면 startup이 거부된다. 정확한 durable scope와
producer·maintenance·intake·executor ownership guard는 그대로 적용한다.

## 관측과 후속 범위

Kafka consumer는 고정 logical consumer별 durable intake/지연, group/partition lag,
quarantine, runtime/rebalance 신호를 낸다. Kafka lag=0은 DB effect 완료가 아니다.
`slotq.observability.database.enabled=true`로 #106의 별도 read-only telemetry pool을
consumer role에 설정하면, 선택된 logical consumer의 DB target PENDING/PROCESSING/DONE/DEAD,
oldest age, due retry와 backoff를 `slotq_kafka_delivery_*`로 별도 표본화한다. 표본 timeout,
staleness, 10,000행 cap은 #106과 같으며 sample health/truncation을 함께 확인한다.
128개 lag tuple 상한은 두 consumer의 configured partition allowlist 합계다.

실제 broker의 absolute end offset이 0인 빈 partition은 첫 committed offset이 없어도 known
lag 0이다. Nonempty partition의 committed provenance 누락, end 조회 실패·범위 오류는 unknown이며
sample health 0을 유지한다. Client position/seek로 durable committed offset을 대신하지 않는다.
Halt/degraded/stop, rebalance 또는 마지막 성공 후 45초 경과 시 기존 lag는 NaN/unknown이며
`SlotqKafkaLagUnavailable`로 탐지한다. 건강한 실제 empty partition의 known lag 0은 유지한다.

#126 corrective 이후 quarantine 관측은 위 opt-in DB observer의 별도 daemon/read-only pool에서
실행한다. Intake cycle과 production JDBC 경로에서 분리했고 connection/connect 1초,
query/MySQL statement 1초, socket 1.5초 bound를 사용한다. 성공한 단일 query의 count/oldest-age
snapshot만 교체한다. 실패·stale은 `slotq_kafka_quarantine_sample_healthy=0`과 값 NaN,
마지막 성공 age로 표시하며 실제 0을 만들지 않는다. 기본 15초 주기/45초 staleness와 고정
failure code를 보존한다. DB observer 미설정은 series 부재/unknown이고 active runtime에서는
`SlotqKafkaQuarantineSampleUnavailable`이 탐지한다. Broker lag와 별개의 sample이다.

DB direct scoped consumer는 같은 ledger를 `slotq_db_delivery_*`, `transport=db`로 표본화한다.
Product의 read-only publication inventory와 expired claim 신호는 publisher process가 죽은
ACK unknown도 탐지한다. Expired lease를 유실이나 business DEAD로 해석하지 않는다.

독립 JVM 전체 fault matrix는 #109, 사람 권한의 business DEAD/publication recovery와 audit은
#110, DB direct 대비 정량 비교와 transport adoption 결정은 #111 소유다. Mixed-version 무중단
전환과 자동 fallback은 지원하지 않는다.
