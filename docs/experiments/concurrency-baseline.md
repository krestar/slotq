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
  "-PhostStorage=NVMe SSD" `
  "-PcontainerLimits=not configured" `
  "-PnetworkCondition=local loopback and Docker bridge; no traffic shaping" `
  "-Poutput=build/reports/experiments/concurrency-baseline.json"
```

같은 commit, host, Docker runtime과 위 parameter를 유지해 같은 workload를 재실행한다.
`clients`는 2 이상이고 `holdDuration`은 분 단위 ISO-8601 Duration이다. 각 반복은
seatingCapacity가 `partySize`인 새 Table과 capacity=1인 Slot을 사용한다. 모든 요청의
Allocation unit은 Product 계약에 따라 1이다.
`strategy`는 다음 두 값만 허용한다.

- `PESSIMISTIC_WRITE_SLOT`: main의 선택된 production Slot row lock을 그대로 실행한다.
- `OPTIMISTIC_SLOT_VERSION_BOUNDED_RETRY`: test runtime에서만 disposable
  `slot_inventories.capacity_version`을 추가하고 compare-and-increment 실패 시 최대 두 번의
  transaction attempt를 실행한다.

Optimistic 후보의 configuration과 decorator는 `src/test`에만 있으며 production artifact,
Flyway schema와 runtime profile에는 포함되지 않는다. 두 후보는 별도 disposable MySQL에서
한 번씩 실행하고, 첫 run의 output은 무시되는 `build/` 아래에 둬 두 번째 run까지 source
revision의 dirty 상태를 바꾸지 않는다.

각 반복의 client는 `CyclicBarrier`에 모두 도착한 뒤 함께 시작한다. runner는 마지막
workload가 끝난 직후 `verificationNow`를 한 번만 캡처하고, 모든 Slot의 effective
occupancy query에 그 값을 그대로 전달한다. `HELD`는 `expiresAt > verificationNow`일
때만 점유한다. raw active Allocation row 수는 별도 diagnostic이며 invariant 판정에는
사용하지 않는다.

## JSON schema

출력은 `slotq-concurrency-baseline/v3` JSON 한 개다. v3는 #15/#16 workload와 request
결과에 summary를 다시 계산할 수 있는 DB/strategy counter 전후 값과 완전한 host/runtime
manifest를 추가한다.

- `environment`: application revision/branch/dirty, MySQL image와 version, transaction
  isolation, Flyway schema version, connection pool, Java/Spring Boot/Gradle/JVM option,
  OS/CPU/core/memory/storage, container limit, network condition과 runner version
- `workload`: clients, iterations, seed, partySize, holdDuration, timeout
- `productModel`: `TABLE_X_SLOT`, slot capacity=1, allocation unit=1, 적용 전략, partySize 역할
- `verificationNow`: 전체 DB 검증에서 공유한 단일 시각
- `databaseCountersBefore/After`, `strategyCountersBefore/After`: lock/deadlock와 optimistic
  retry의 변경 전후 원자료
- `metrics`: 위 counter와 request/slot 원자료에서 계산한 throughput, P50/P95/P99,
  business conflict, system failure, timeout,
  successful HOLD result, invariant violation, effective occupancy, raw-active diagnostic,
  Reservation/Allocation partial commit, stale/system retry와 exhaustion, MySQL row-lock wait/time,
  deadlock, barrier release 이후 최대 request-start spread
- `slotObservations`: 반복별 effective occupancy, raw-active, Reservation/Allocation row 수와
  partial commit 판정
- `requests`: 반복/client별 outcome, HTTP status 또는 오류 분류와 latency

`hostStorage`는 저장장치의 유형만 기록하며 사용자명이나 filesystem path를 넣지 않는다.
명시적인 container CPU/memory 제한이 없으면 `not configured`라고 기록한다. `requests`에는
credential이나 요청 payload를 기록하지 않는다. performance 수치와
baseline의 invariant violation 유무는 CI pass/fail 조건이 아니다. 기본 `test` task에는
parameter validation, barrier 동작과 실제 MySQL/Product 경로에서 capacity=1, partial commit
부재와 authoritative `CAPACITY_UNAVAILABLE`을 반복 확인하는 correctness smoke만 포함한다.

#16의 동일 조건 후보 비교 결과는
[Reservation 동시성 전략 비교](concurrency-strategy-comparison.md)에 기록한다.
