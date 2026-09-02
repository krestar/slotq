# SlotQ Experiment Plan

이 문서는 동시 예약과 event processing의 correctness, 성능, 실패 복구를 재현 가능한
방식으로 검증하기 위한 기준을 정의한다. 아직 실행하지 않은 실험의 결과나 수치를
미리 작성하지 않는다.

## 공통 원칙

- correctness를 performance보다 먼저 판단한다.
- 모든 run은 실행 code의 commit SHA와 변경 유무를 기록한다.
- 비교 대상은 같은 hardware, runtime, database, schema, dataset과 workload에서 실행한다.
- business conflict, retry exhaustion, timeout, infrastructure failure를 하나의 failure rate로
  섞지 않는다.
- raw data, environment manifest와 분석 과정을 함께 보존한다.
- 불리하거나 예상과 다른 결과도 삭제하지 않고 원인과 후속 조건을 기록한다.
- Secret, credential, 개인정보, 원문 요청 payload를 결과물에 남기지 않는다.
- benchmark는 고정 환경을 보장하기 어려우므로 기본 PR merge gate로 사용하지 않는다.
  CI에는 짧고 안정적인 correctness test만 둔다.

## 결과 상태

실험 문서는 다음 상태 중 하나를 명시한다.

- Planned: 환경과 방법만 정의되었고 아직 측정하지 않았다.
- Running: 실행 중이며 결론을 내리지 않는다.
- Measured: raw data와 environment manifest가 있으며 분석을 재현할 수 있다.
- Inconclusive: 측정은 했지만 환경 차이, 표본 부족, 오류 등으로 결론을 내릴 수 없다.

Measured가 아닌 문서에는 전략 우열이나 목표 달성 수치를 결론으로 작성하지 않는다.

## 공통 environment manifest

각 run에는 최소한 다음 값을 기록한다.

| 구분 | 기록 항목 |
| --- | --- |
| Source | commit SHA, branch, dirty 여부, 실험 ID, 실행 시각 |
| Host | OS, CPU model과 할당 core, memory, storage 유형 |
| Runtime | JDK, Spring Boot, build tool, JVM option |
| Database | MySQL image와 version, transaction isolation, schema version |
| Connection | pool size, timeout, retry와 backoff 설정 |
| Data | tenant, Resource, Slot, capacity, 기존 Reservation 수, seed |
| Tooling | load generator와 분석 script version |
| Limits | container CPU·memory limit, network condition |

권장 evidence 구조는 다음과 같다.

    docs/experiments/
      concurrency/
        <run-id>/
          environment.md
          workload.md
          raw/
          summary.md
      events/
        <run-id>/
          environment.md
          fault-matrix.md
          raw/
          summary.md

실제 run을 수행하는 Issue에서만 위 디렉터리를 추가한다.

## Reservation concurrency

현재 Product HOLD의 naive check-then-write 측정 절차와 JSON 계약은
[Product HOLD concurrency experiment](concurrency-baseline.md)에 기록한다. #16의 동일 조건
후보 비교와 선택 근거는 [Reservation 동시성 전략 비교](concurrency-strategy-comparison.md)에
기록한다.

### 질문

- 동시에 마지막 capacity를 요청해도 invariant가 유지되는가?
- correctness를 만족하는 전략 중 현재 구조에서 가장 단순한 전략은 무엇인가?
- contention이 증가할 때 throughput, tail latency, retry와 lock wait가 어떻게 변하는가?

검증할 invariant는 다음과 같다.

    sum(quantity of capacity-consuming reservations) <= slot capacity

Capacity-consuming 상태는 만료되지 않은 HELD, CONFIRMED, CHECKED_IN이다. 최종 상태는
애플리케이션 response가 아니라 database를 다시 조회해 계산한다.

### 비교 후보

1. Naive check-then-insert
   - race를 관찰하기 위한 baseline이다.
   - correctness 전략으로 main에 남기지 않는다.
2. Optimistic Lock
   - retry 없음과 bounded retry를 구분한다.
   - retry 횟수와 backoff를 고정해 기록한다.
3. Pessimistic Lock
   - 어떤 row를 언제부터 언제까지 잠그는지 기록한다.
   - lock 범위를 business transaction보다 넓히지 않는다.
4. Distributed Lock
   - DB 전략이 correctness를 충족하지 못하거나 다중 writer에서 DB lock이 측정된 병목일
     때만 별도 후보로 연다.
   - 단순 비교 항목 수를 늘리기 위해 도입하지 않는다.

### Workload matrix

최초 matrix는 작게 시작하고 결과가 포화 지점을 보여 줄 때 확장한다.

MVP correctness matrix는 Restaurant의 배타적 Table 모델과 일치시킨다.

| 축 | MVP 초기 값 |
| --- | --- |
| Resource model | 배타적 `Table × Slot` |
| Slot capacity | 1 |
| Concurrent client | 2, 10, 50 |
| Request quantity | 모두 1 |
| Contention | 하나의 hotspot Table Slot, 여러 비경합 Table Slot control |
| Initial state | empty, HELD, CONFIRMED |
| Run type | warm-up, correctness 반복, fixed-operation performance |

Capacity 10·100과 혼합 quantity는 pooled capacity가 실제 Product 범위에 들어온 뒤 여는
별도 model stress scenario다. MVP 결과와 같은 표에 섞지 않으며, 이 선택적 결과를 현재
Restaurant 모델이나 production 규모의 처리 능력으로 해석하지 않는다.

- 요청은 barrier에서 동시에 시작한다.
- correctness run은 동일 matrix와 seed로 반복한다.
- performance run 전에는 별도 warm-up을 수행하고 warm-up 결과는 측정값에서 제외한다.
- connection pool보다 많은 client를 사용할 때는 pool wait와 DB lock wait를 분리해 기록한다.
- 동일 Slot hotspot과 여러 Slot control 결과를 나란히 비교한다.

### 실행 절차

1. clean database에 지정 migration을 적용한다.
2. environment manifest와 고정 seed를 저장한다.
3. tenant, Venue, Resource, Slot과 초기 Reservation을 생성한다.
4. warm-up을 수행한다.
5. barrier를 사용해 workload를 실행한다.
6. database에서 최종 Reservation과 점유 quantity를 다시 집계한다.
7. application metric과 MySQL metric을 함께 수집한다.
8. raw data를 변경하지 않은 채 저장하고 별도 summary를 작성한다.
9. 같은 조건으로 다른 전략을 실행한다.

### Metrics

Correctness:

- invariant violation count.
- 생성된 capacity-consuming Reservation 수와 quantity.
- duplicate Reservation count.
- illegal state transition count.

Request:

- throughput.
- P50, P95, P99 latency.
- business conflict rate.
- optimistic conflict와 retry count.
- retry exhaustion, timeout, 5xx rate.

Database:

- connection acquisition wait.
- row lock wait.
- deadlock count.
- query count와 transaction duration.
- CPU, active connection과 slow query.

### 판단 기준

1. invariant violation이 있는 전략은 performance와 관계없이 탈락한다.
2. correctness를 만족한 전략끼리만 tail latency, throughput, failure 분류와 DB 부담을
   비교한다.
3. correctness가 같다면 운영과 구현이 더 단순한 전략을 우선한다.
4. bounded retry가 tail latency 또는 DB load를 악화시키면 retry 조건과 횟수를 줄이고
   다시 측정한다.
5. 한 환경의 우세를 일반 법칙으로 표현하지 않고 적용 조건과 재검토 trigger를 ADR에
   남긴다.

### Evidence

- 실행 가능한 runner와 workload configuration.
- environment manifest.
- timestamp가 있는 raw JSON 또는 CSV.
- 전략별 correctness와 latency summary.
- MySQL lock·deadlock 자료.
- 선택과 기각 이유, 재검토 조건을 담은 ADR.
- 짧고 반복 가능한 CI invariant test.

## Event processing

Event experiment는 M3 Reliable Event Foundation 착수 전까지 Planned 상태다. 특정 broker나
Transactional Outbox 채택을 전제하지 않고 DB commit과 event 전달 사이의 유실 문제부터
재현한 뒤 가장 단순한 대안을 선택한다.

### 질문

- 업무 transaction commit과 event 전달 사이의 crash에서 event를 복구할 수 있는가?
- duplicate publish와 redelivery가 duplicate business side effect를 만드는가?
- retry, process restart와 partial failure 뒤 backlog가 얼마나 안전하게 회복되는가?
- operator replay가 동일한 idempotency와 audit 규칙을 따르는가?

### 비교할 전달 경계

1. 같은 transaction 안의 동기 처리
   - 원자성은 단순하지만 느리거나 실패하는 후속 작업이 Product transaction을 늘리는
     비용을 측정한다.
2. commit 이후 직접 publish
   - DB commit 직후 process crash에서 유실 가능한 baseline으로 사용하며 복구 수단 없이
     신뢰성 전략으로 채택하지 않는다.
3. Transactional Outbox와 relay
   - 업무 상태와 durable record를 한 transaction에 기록하고 중복 publish를 consumer가
     흡수하는 비용을 검증한다.
4. Broker transaction 또는 별도 delivery infrastructure
   - DB 기반 후보로 충족하지 못하는 fan-out, isolation, throughput 요구가 측정될 때만
     비교 범위에 추가한다.

### Outbox 후보 topology

1. Product transaction이 업무 상태와 outbox record를 함께 commit한다.
2. relay가 미전송 outbox record를 읽어 publish한다.
3. consumer가 event ID 또는 business unique key로 중복을 식별한다.
4. consumer side effect와 처리 기록을 가능한 한 같은 transaction에 반영한다.
5. retry exhaustion은 조회 가능한 dead-letter 상태로 이동한다.

Outbox를 선택해 검증할 때는 DB polling relay를 최초 구현 후보로 둔다. fan-out, consumer
isolation 또는 throughput 요구가 DB relay로 충족되지 않는다는 측정이 있을 때 broker 비교
실험을 추가한다.

### Event workload

| 축 | 초기 값 |
| --- | --- |
| Event volume | 작은 correctness batch부터 시작하고 측정 후 확대 |
| Duplicate | 같은 event ID의 연속·지연 duplicate |
| Consumer concurrency | 1과 복수 consumer |
| Ordering | 동일 aggregate 순서, 서로 다른 aggregate interleave |
| Failure | 지정 횟수 exception, process kill, dependency timeout |
| Recovery | relay restart, consumer restart, operator replay |

고정 event fixture에는 event ID, aggregate ID, tenant ID, schema version과 발생 순서를
포함한다. 개인정보 payload는 사용하지 않는다.

### Fault matrix

| 지점 | 주입 방법 | 반드시 검증할 결과 |
| --- | --- | --- |
| 업무 transaction commit 전 | commit 직전 exception 또는 process 종료 | 업무 상태와 outbox가 모두 남지 않는다. |
| commit 후 relay 전 | commit 직후 process 종료 | restart 후 outbox가 발견되어 처리된다. |
| publish 후 sent 표시 전 | publish 직후 relay 종료 | redelivery되어도 business side effect는 한 번이다. |
| consumer side effect 전 | handler exception | side effect 없이 retry 상태가 기록된다. |
| side effect commit 후 ack 전 | commit 직후 consumer 종료 | redelivery되어도 side effect가 추가되지 않는다. |
| 중복 event | 같은 event ID를 여러 번 전달 | 처리 결과와 business unique row가 하나다. |
| 순서 역전 | 같은 aggregate의 후속 event를 먼저 전달 | 명시한 ordering 정책에 따라 지연·거부·보정된다. |
| retry exhaustion | handler가 계속 실패하도록 구성 | dead-letter 상태와 마지막 오류가 조회된다. |
| operator replay | dead-letter event를 다시 실행 | 같은 idempotency와 authorization·audit가 적용된다. |
| 외부 dependency timeout | 응답 전후 timeout을 각각 주입 | 성공 여부가 불명확한 호출도 idempotency key로 중복되지 않는다. |
| relay restart | backlog 도중 process restart | 미처리 backlog가 누락 없이 drain된다. |
| consumer restart | 처리 중 process restart | committed side effect가 중복되지 않고 나머지가 처리된다. |

각 항목은 최소 한 번의 happy path와 반복 failure run을 가진다. crash point를 모호하게
표현하지 않고 test hook 또는 process 종료 위치를 기록한다.

### Metrics

Correctness:

- committed outbox 대비 processed, pending, dead-letter count.
- lost event count.
- duplicate delivery count.
- duplicate business side effect count.
- ordering violation과 illegal transition count.

Recovery:

- retry count와 retry exhaustion count.
- time to recovery.
- outbox와 consumer lag.
- backlog drain time.
- operator replay 성공·실패 count.

Performance:

- publish throughput.
- consumer throughput.
- event 발생부터 side effect commit까지 P50, P95, P99.
- relay query와 database load.

### 판단 기준

1. commit된 event가 정의한 recovery 절차 뒤에도 처리되지 않으면 전달 설계는 탈락한다.
2. duplicate business side effect가 발생하면 idempotency 설계는 탈락한다.
3. retryable과 non-retryable failure를 구분하지 못하거나 무한 retry가 가능하면 완료로
   판단하지 않는다.
4. operator replay가 authorization, tenant boundary와 audit를 우회하면 완료로 판단하지
   않는다.
5. broker 도입은 correctness 자체가 아니라 측정된 fan-out, isolation, throughput 또는
   운영 요구를 해결하는 경우에만 선택한다.

### Evidence

- event schema와 version 규칙.
- outbox, inbox 또는 business unique constraint schema.
- fault injection code와 실행 명령.
- fault matrix별 raw log와 database snapshot.
- duplicate, redelivery, crash, partial failure test report.
- recovery time과 backlog drain raw data.
- operator replay audit.
- transport 선택 또는 보류 ADR.

## 결과 보고 규칙

각 summary는 다음 질문에 답해야 한다.

1. 어떤 문제를 검증했는가?
2. 실행 환경과 workload는 무엇인가?
3. 어떤 대안을 같은 조건에서 비교했는가?
4. correctness gate를 모두 통과했는가?
5. raw data로 다시 계산할 수 있는가?
6. 결론의 적용 범위와 한계는 무엇인가?
7. 다음 결정이나 재검토 trigger는 무엇인가?

숫자는 raw data가 있는 Measured run에서만 사용한다. 목표치, 예상치와 측정치를 같은
표에 둘 때는 열을 분리하고, 측정되지 않은 칸은 미측정으로 표시한다.
