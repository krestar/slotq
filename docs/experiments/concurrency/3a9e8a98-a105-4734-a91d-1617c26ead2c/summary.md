# Optimistic concurrency run summary

- 상태: Measured
- 전략: OPTIMISTIC_SLOT_VERSION_BOUNDED_RETRY
- 원자료: [report.json](raw/report.json)
- 환경: [environment.md](environment.md)
- workload: [workload.md](workload.md)

| Metric | 측정값 |
| --- | ---: |
| 요청 / successful HOLD / CAPACITY_UNAVAILABLE | 50 / 5 / 45 |
| system failure / timeout | 0 / 0 |
| invariant violation / partial commit | 0 / 0 |
| effective occupancy / raw active Allocation | 5 / 5 |
| throughput | 79.2907 req/s |
| P50 / P95 / P99 | 85.3129 / 256.3343 / 262.6809ms |
| stale retry / exhaustion | 45 / 0 |
| system retry / exhaustion | 0 / 0 |
| MySQL row-lock wait / time | 45 / 1010ms |
| deadlock | 0 |
| 최대 barrier release start spread | 0.3361ms |

각 Slot에는 Reservation과 Allocation이 각각 하나만 commit됐고 동일 verificationNow의
effective occupancy도 1이었다. 나머지 요청은 stale 검출 뒤 새 transaction에서 current
capacity를 재평가해 CAPACITY_UNAVAILABLE로 끝났다.

별도 warm-up이 없는 단일 local run이므로 latency와 throughput은 production 보장이나
전략의 일반적인 우위를 뜻하지 않는다. 이 후보는 비교 재현용 test harness이며 Product
schema, runtime profile 또는 운영 retry 정책에 포함되지 않는다.
