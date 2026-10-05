# Pessimistic concurrency run summary

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

- 상태: Measured
- 전략: PESSIMISTIC_WRITE_SLOT
- 환경: [environment.md](environment.md)
- workload: [workload.md](workload.md)

| Metric | 측정값 |
| --- | ---: |
| 요청 / successful HOLD / CAPACITY_UNAVAILABLE | 50 / 5 / 45 |
| system failure / timeout | 0 / 0 |
| invariant violation / partial commit | 0 / 0 |
| effective occupancy / raw active Allocation | 5 / 5 |
| throughput | 36.0074 req/s |
| P50 / P95 / P99 | 166.7742 / 392.0473 / 440.2491ms |
| stale retry / exhaustion | 0 / 0 |
| system retry / exhaustion | 0 / 0 |
| MySQL row-lock wait / time | 45 / 6095ms |
| deadlock | 0 |
| 최대 barrier release start spread | 0.3973ms |

각 Slot에는 Reservation과 Allocation이 각각 하나만 commit됐고 동일 verificationNow의
effective occupancy도 1이었다. 나머지 요청은 Slot row lock 뒤 current capacity를 읽어
CAPACITY_UNAVAILABLE로 끝났다.

별도 warm-up이 없는 단일 local run이므로 latency와 throughput은 production 보장이나
전략의 일반적인 열세를 뜻하지 않는다. correctness가 같은 현재 capacity=1 모델에서는
추가 schema와 retry orchestration이 없는 이 전략을 계속 선택한다.
