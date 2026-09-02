# Product HOLD concurrency experiment

이 하네스는 Product HOLD transaction의 동시 요청 결과를 같은 schema로 재현·측정한다.
매 실행은 disposable MySQL 8.4에
Flyway migration을 적용하고 실제 Spring Boot HTTP 서버의
`POST /api/v1/venues/{venueId}/reservations/holds`를 호출한다. 요청에는
`Idempotency-Key`를 보내지 않는다.

## 실행

Backend project에서 다음처럼 실행한다.

```powershell
.\gradlew.bat concurrencyBaseline `
  "-Pclients=10" `
  "-Piterations=20" `
  "-Pseed=15001" `
  "-PpartySize=2" `
  "-PholdDuration=PT5M" `
  "-Ptimeout=PT10S" `
  "-Pstrategy=PESSIMISTIC_WRITE_SLOT" `
  "-Poutput=build/reports/experiments/concurrency-baseline.json"
```

같은 commit, host, Docker runtime과 위 parameter를 유지해 같은 workload를 재실행한다.
`clients`는 2 이상이고 `holdDuration`은 분 단위 ISO-8601 Duration이다. 각 반복은
seatingCapacity가 `partySize`인 새 Table과 capacity=1인 Slot을 사용한다. 모든 요청의
Allocation unit은 Product 계약에 따라 1이다.
`strategy`는 결과에 남기는 구현 식별자이며 runtime에 다른 Product 전략을 활성화하는
feature flag가 아니다.

각 반복의 client는 `CyclicBarrier`에 모두 도착한 뒤 함께 시작한다. runner는 마지막
workload가 끝난 직후 `verificationNow`를 한 번만 캡처하고, 모든 Slot의 effective
occupancy query에 그 값을 그대로 전달한다. `HELD`는 `expiresAt > verificationNow`일
때만 점유한다. raw active Allocation row 수는 별도 diagnostic이며 invariant 판정에는
사용하지 않는다.

## JSON schema

출력은 `slotq-concurrency-baseline/v2` JSON 한 개다. v2는 #15의 workload와 request
결과를 유지하면서 #16의 전략 비교 metric을 추가한다.

- `environment`: application revision/branch/dirty, MySQL image와 version, transaction
  isolation, Flyway schema version, connection pool, Java/Spring Boot/Gradle과 host 정보
- `workload`: clients, iterations, seed, partySize, holdDuration, timeout
- `productModel`: `TABLE_X_SLOT`, slot capacity=1, allocation unit=1, 적용 전략, partySize 역할
- `verificationNow`: 전체 DB 검증에서 공유한 단일 시각
- `metrics`: throughput, P50/P95/P99, business conflict, system failure, timeout,
  successful HOLD result, invariant violation, effective occupancy, raw-active diagnostic,
  Reservation/Allocation partial commit, stale/system retry와 exhaustion, MySQL row-lock wait/time,
  deadlock, barrier release 이후 최대 request-start spread
- `slotObservations`: 반복별 effective occupancy, raw-active, Reservation/Allocation row 수와
  partial commit 판정
- `requests`: 반복/client별 outcome, HTTP status 또는 오류 분류와 latency

`requests`에는 credential이나 요청 payload를 기록하지 않는다. performance 수치와
baseline의 invariant violation 유무는 CI pass/fail 조건이 아니다. 기본 `test` task에는
parameter validation, barrier 동작과 실제 MySQL/Product 경로에서 capacity=1, partial commit
부재와 authoritative `CAPACITY_UNAVAILABLE`을 반복 확인하는 correctness smoke만 포함한다.

#16의 동일 조건 후보 비교 결과는
[Reservation 동시성 전략 비교](concurrency-strategy-comparison.md)에 기록한다.
