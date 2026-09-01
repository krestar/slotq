# Product HOLD concurrency baseline

이 하네스는 lock 전략을 선택하거나 Product correctness를 수정하지 않고, 현재 Product
HOLD transaction의 동시 요청 결과를 재현·측정한다. 매 실행은 disposable MySQL 8.4에
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
  "-Poutput=build/reports/experiments/concurrency-baseline.json"
```

같은 commit, host, Docker runtime과 위 parameter를 유지해 같은 workload를 재실행한다.
`clients`는 2 이상이고 `holdDuration`은 분 단위 ISO-8601 Duration이다. 각 반복은
seatingCapacity가 `partySize`인 새 Table과 capacity=1인 Slot을 사용한다. 모든 요청의
Allocation unit은 Product 계약에 따라 1이다.

각 반복의 client는 `CyclicBarrier`에 모두 도착한 뒤 함께 시작한다. runner는 마지막
workload가 끝난 직후 `verificationNow`를 한 번만 캡처하고, 모든 Slot의 effective
occupancy query에 그 값을 그대로 전달한다. `HELD`는 `expiresAt > verificationNow`일
때만 점유한다. raw active Allocation row 수는 별도 diagnostic이며 invariant 판정에는
사용하지 않는다.

## JSON schema

출력은 `slotq-concurrency-baseline/v1` JSON 한 개다.

- `environment`: application revision/branch/dirty, MySQL image와 version, transaction
  isolation, Flyway schema version, connection pool, Java/Spring Boot/Gradle과 host 정보
- `workload`: clients, iterations, seed, partySize, holdDuration, timeout
- `productModel`: `TABLE_X_SLOT`, slot capacity=1, allocation unit=1, partySize 역할
- `verificationNow`: 전체 DB 검증에서 공유한 단일 시각
- `metrics`: throughput, P50/P95/P99, business conflict, system failure, timeout,
  successful HOLD result, invariant violation, effective occupancy, raw-active diagnostic,
  barrier release 이후 최대 request-start spread
- `slotObservations`: 반복별 effective occupancy와 raw-active diagnostic
- `requests`: 반복/client별 outcome, HTTP status 또는 오류 분류와 latency

`requests`에는 credential이나 요청 payload를 기록하지 않는다. performance 수치와
baseline의 invariant violation 유무는 CI pass/fail 조건이 아니다. 기본 `test` task에는
parameter validation, barrier 동작과 실제 MySQL/Product 경로를 짧게 확인하는 correctness
smoke만 포함한다.
