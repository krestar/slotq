# Product metric / dashboard 계약

DB는 business authority이고 metric은 비동기 advisory sample이다. HTTP scrape는 JDBC를 호출하지 않는다. opt-in observer는 별도 daemon과 max-one connection pool, query별 1초 timeout/MySQL MAX_EXECUTION_TIME, socket 1.5초/connection 1초 timeout을 사용한다. Product worker scheduler, pool, transaction manager를 공유하지 않는다. 각 sample은 여러 autocommit 조회이므로 원자적인 전역 snapshot이나 correctness oracle이 아니다.

각 조회는 최대 10,001행까지만 읽고 10,000개만 집계한다. query sort/join도 MySQL statement time budget의 적용 대상이다. 표본 cap은 결과 수/메모리 한도이며 전체 table scan이 최대 10,000행이라는 뜻은 아니다. timeout이면 invalid sample로 처리한다. interval 기본 15초, 마지막 성공 45초 초과 또는 최근 실패면 sample healthy=0과 값 NaN으로 표시한다. 최초 미수집/disabled는 series 부재다. 누락값을 0으로 보정하지 않는다.

| Prometheus signal | 단위와 의미 |
| --- | --- |
| `slotq_http_requests_seconds_*` | server route template × method × status_class × fixed outcome의 요청 수/elapsed histogram. 인증 실패도 포함 |
| `slotq_db_event_undiscovered` | discovery cursor 뒤 actual original event_records 수. route lifecycle로 생긴 boundary gap 제외. cap lower bound, 모든 event type이며 eligible target 수가 아님 |
| `slotq_db_delivery_targets{delivery_state}` | waitlist.promotion의 original registration target inventory. PENDING/PROCESSING/DONE/DEAD 별도 oldest-first cap |
| `slotq_db_delivery_oldest_created_age_seconds{delivery_state}` | target created_at 기준 oldest age. event occurredAt/commit latency가 아님. 상태별 oldest-first로 선택 |
| `slotq_db_business_outstanding_targets{delivery_state}` | 해당 materialized target 중 완료 promotion receipt 없는 target 수. logical event unique 수가 아님 |
| `slotq_db_promotion_receipts{promotion_outcome}` | PROMOTED 및 NO_CAPACITY/NO_CANDIDATE/NOT_ELIGIBLE/SLOT_PAST/DEFERRED 완료 receipt inventory. occurred_at 순 cap이며 rate/throughput으로 사용 금지 |
| `slotq_db_promotion_requests_outstanding` | last admitted event에 완료 receipt가 없는 promotion request 수. 아직 미물질화된 request 포함. capacity-release event 전체 backlog를 대신하지 않음 |
| `slotq_db_delivery_retry_due` | PENDING 표본 중 lifetime attempts>0, next_attempt_at<=DB now 대상 수 |
| `slotq_db_delivery_lifetime_attempts`, `slotq_db_delivery_cycle_attempts` | target 표본의 attempt 누적값 inventory. retry event counter/효과 수 아님 |
| `slotq_db_lock_waits` | global performance_schema.data_lock_waits 현재 row 수, cap lower bound |
| `slotq_db_lock_waits_cumulative`, `slotq_db_lock_wait_milliseconds_cumulative`, `slotq_db_lock_deadlocks_cumulative` | MySQL status/instrument 누적값을 gauge로 노출. DB restart reset 가능. delta 음수는 개선이 아니며 instrument disabled는 absent |
| `slotq_observation_sample_healthy{sample}` / `sample_age_seconds` | events/locks 독립 성공·freshness. missing/NaN/0을 구별 |
| `slotq_db_sample_truncated{sample}` | 1이면 해당 sample cap 도달, count lower bound. delivery는 각 상태 독립 cap이라 오래된 DONE이 신규 DEAD를 숨기지 않음 |
| `slotq_maintenance_cycles_total`, `slotq_maintenance_duration_seconds_*`, `slotq_maintenance_failures_total{kind}` | maintenance success/failure/disabled 및 hold/offer/entry/request failure 분리 |
| `slotq_delivery_cycles_total`, `slotq_delivery_cycle_duration_seconds_*` | worker cycle success/failure 및 elapsed. business effect 수와 다름 |
| `slotq_delivery_effect_duration_seconds_count`, `_sum`, `_max` | effect 시도 시작부터 transaction completion 관측까지의 elapsed. `outcome=committed\|rolled_back\|unknown\|ownership_lost`를 분리. 현재 production consumer가 waitlist.promotion 하나이므로 outcome만 tag로 사용하며 임의 consumer ID를 붙이지 않음. committed에는 정상 no-op 포함; outcome unknown을 rollback으로 집계하지 않음 |
| `slotq_telemetry_export_spans_total{outcome}` | async exporter batch span success/failure. disabled exporter는 가짜 성공을 만들지 않음 |
| `hikaricp_*`, `jvm_*` | Spring 기본 pool/JVM bounded metric |

`transport=db`, `runtime_role=observer`, 고정 `logical_consumer=waitlist.promotion`, enum state/outcome/sample을 사용한다. UUID, tenant/principal, request/event/Entry/Offer/Reservation identity, idempotency key, raw URL/query, exception message, credential, payload/SQL parameter는 label로 금지한다. app meter filter는 승인되지 않은 dimension/value를 거부한다. HTTP route/outcome은 request filter가 server mapping과 고정 분류에서만 만든다.

Count가 0이어도 sample truncated=1 또는 healthy=0이면 global 부재를 주장할 수 없다. DONE target, completed receipt, PROMOTED effect는 서로 다른 집계다. 여러 registration target이 같은 logical receipt를 가리킬 수 있다. 여러 app replica의 global DB inventory를 `sum`하면 중복 합산되므로 instance별 또는 `max`를 사용한다.

## 후속 Kafka contract

현재 아래 metric은 등록/emit하지 않는다. Dashboard는 실제 series를 조회하고 absent 상태를 **NO SIGNAL / UNKNOWN**으로 보여 준다. HTTP API의 오류를 0으로 변환하거나 `or vector(0)`를 쓰지 않는다.

| 예약 signal | ownership / query 의미 |
| --- | --- |
| `slotq_kafka_publication_ack_total{outcome="success\|failure"}` | #107. broker publish 시도의 ack 성공/실패 관측 counter. retry의 중복 ack가 포함될 수 있어 unique event 수, DB commit 수 또는 consumer intake 수가 아님 |
| `slotq_kafka_publication_pending_events` | #107. relay가 소유하는 publication 책임 중 완료 ack가 아직 확인되지 않은 original event inventory. transport authority/원 membership은 #105를 보존. 새 publication ledger/schema를 #106이 지정하지 않음 |
| `slotq_kafka_publication_oldest_recorded_age_seconds` | #107. 위 pending 집합에서 가장 오래된 event의 recorded_at 기준 age. occurredAt 또는 transaction commit timestamp로 부르지 않음 |
| `slotq_kafka_publication_retry_total` | #107. 첫 publish 이후 재시도 시작 횟수. retry가 성공해도 business effect 수에 더하지 않음 |
| `slotq_kafka_publication_failures_total{failure_code}` | #107. publish 실패 관측 횟수. 고정 `TIMEOUT`, `CONNECTION`, `AUTHORIZATION`, `SERIALIZATION`, `OTHER`만 허용. exception message/broker URL 금지 |
| `slotq_kafka_runtime_state{runtime_role="relay\|producer",state="ready\|degraded\|stopped"}` | #107. 각 configured runtime의 one-hot 현재 상태 gauge. 0/1을 emit할 때는 상태를 실제로 확인한 경우만 가능; 미설치/미수집을 stopped=1로 위조하지 않음 |
| `slotq_kafka_intake_total{outcome="success\|failure"}` | #108. original target의 durable intake 확인/실패 시도 수. idempotent 재확인도 포함할 수 있어 unique target 수, offset commit 또는 business completion 아님 |
| `slotq_kafka_durable_intake_observed_delay_seconds_bucket`, `_sum`, `_count` | #108. original event의 DB recorded_at부터 target + provenance durable commit 후 fresh DB 조회의 DB UTC까지 관측된 지연 histogram. 실제 commit latency가 아닌 observed upper interval이며 broker ack→intake만의 구간도 아님. 기존 durable target 재확인은 latency 표본에서 제외. timestamp가 없거나 음수이면 표본을 만들지 않고 아래 invalid counter 사용 |
| `slotq_kafka_intake_delay_invalid_total{reason="missing_timestamp\|clock_order"}` | #108. 지연을 정직하게 계산할 수 없는 관측 수. 잘못된 시간을 0초로 clamp하지 않음 |
| `slotq_kafka_consumer_lag_records{logical_consumer,consumer_group,topic,partition}` | #108. configured group/topic/partition의 log-end offset과 committed durable-intake prefix offset 차이. offset **값**은 label로 금지. group/partition label은 아래 고정 allowlist와 cap 내에서 허용. lag=0은 effect 완료를 증명하지 않음 |
| `slotq_kafka_lag_sample_healthy`, `slotq_kafka_lag_sample_truncated` | #108. lag 관측의 freshness/권한/offset 범위 오류와 configured series cap 초과를 별도 표시. healthy=0 또는 truncated=1인 부분합을 정상 전체 lag로 주장하지 않음 |

이 표의 metric naming, 단위, timestamp source, cardinality 및 missing 의미는 #106 관측 계약이다. 실제 측정/emit, publication 책임 데이터, Kafka client lifecycle은 #107, intake provenance/offset/lag 수집 구현은 #108이 소유한다. 공통 label은 publication에 `transport=kafka,runtime_role=relay`, intake/lag에 `transport=kafka,runtime_role=consumer`를 사용한다. intake/delay/lag/lag sample의 `logical_consumer`는 #108에서 확정한 `waitlist.promotion`, `operations.event-observation` 두 값만 허용한다. 임의 consumer, process/replica/registration UUID는 이 label로 허용하지 않는다. runtime state만 별도 role enum을 사용한다.

현재 DB direct sampler는 계속 `waitlist.promotion`의 delivery/promotion만 emit한다. 이 allowlist 보완은 observer projection, intake 또는 consumer runtime을 추가하지 않는다. Kafka panel은 각 consumer를 legend에 표시하고 delay/invalid-delay/lag 집계에도 `logical_consumer`를 보존한다. 한 consumer의 지연·오류·lag를 다른 consumer와 합쳐 정상 상태처럼 표시하지 않는다. 128개 lag tuple 상한은 두 consumer를 합한 전체 관측 상한이다.

event별 metric을 분해할 때 `event_type`은 현재 확정된 `booking.capacity-released`, `waitlist.promotion-requested` 두 값, `schema_version`은 문자열 `1`만 허용한다. 현재 두 production route의 v1 vocabulary를 #106에서 고정한다. 임의 event type/version 문자열을 label로 등록하지 않는다. 향후 event/version 추가는 명시적인 allowlist 및 series budget 변경으로 처리한다. publication inventory/ack/retry/failure 및 intake/delay에만 필요한 event dimension을 사용하고 runtime state/lag에 무조건 교차곱으로 추가하지 않는다.

`consumer_group`과 `topic`은 배포 설정에서 열거한 allowlist만 허용하고 discovery로 발견한 임의 group/topic을 자동 등록하지 않는다. `partition`은 해당 configured topic의 명시적인 정수 집합에 속해야 하며 전체 `(logical_consumer, consumer_group, topic, partition)` 조합은 **128개**를 상한으로 한다. cap 초과 시 새 series 생성을 중단하고 lag_sample_truncated=1로 표시한다. 이는 runtime 전체 partition 수 제한이 아닌 관측 series budget이다. 실제 group/topic 명칭과 partition 배치는 #107/#108 배포 구현에서 이 계약 내에 설정한다. group/partition별 panel은 이 bounded label을 사용하며 logical consumer 총량 panel은 `sum by (logical_consumer,consumer_group)`으로 partition을 합산한다. 동일 group lag를 여러 replica에서 수집하면 먼저 `max by (logical_consumer,consumer_group,topic,partition)`으로 중복을 제거한다.

intake observed delay의 fresh DB 조회 구간은 #105의 clock/source 구분을 따른다. timeout/관측 실패를 Product intake transaction에 되돌리지 않으며 기록된 elapsed는 best-effort telemetry다. offset retention 이탈/음수 lag/알 수 없는 offset을 healthy 0으로 바꾸지 않는다. 새 구현이 현재 meter filter에 예약 label을 추가할 때는 위 fixed allowlist와 negative cardinality regression을 함께 적용한다. #106은 예약 Kafka meter를 등록하지 않으므로 미설치 상태에서 가짜 0 series가 생기지 않는다.

이 문서는 delivery target identity, routing/cutover, receipt/fencing/replay 또는 canonical event version을 바꾸지 않는다. #111이 최종 DB/Kafka 비교와 threshold를 소유한다.

## 실행 가능한 query와 alert

실제 [provisioned dashboard](../../infra/observability/grafana/dashboards/slotq-product.json)와 [Prometheus rules](../../infra/observability/alerts.yml)가 query/panel의 단일 소스다. Request p95는 `slotq.http.requests`의 명시적 SLO buckets **100ms, 500ms, 1s, 5s, 10s**를 사용한 `histogram_quantile` 근사값이다. publishPercentileHistogram으로 더 세밀한 bucket을 생성하지 않으며 범위 밖/표본 부족 결과를 정확한 percentile로 주장하지 않는다. Effect timer는 count/sum/max를 제공하므로 outcome별 평균(`rate(sum)/rate(count)`)과 시도 수를 표시하며 존재하지 않는 effect histogram을 가정하지 않는다. DB inventory에 `rate`를 적용하지 않는다. 알림은 scrape unavailable, sample unavailable/stale, 오래된 backlog, DEAD, 실제 lock wait, truncated sample, exporter failure를 분리한다. 로컬 threshold는 #111의 production/adoption threshold가 아니다.

[setup와 재현](../../infra/observability/README.md), [correlation/security 계약](product-observability.md).
