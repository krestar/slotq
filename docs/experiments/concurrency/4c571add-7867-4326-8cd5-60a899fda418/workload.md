# Pessimistic concurrency run workload

## 실행 명령

backend 디렉터리의 PowerShell에서 다음 명령을 실행했다.

    .\gradlew.bat --no-daemon concurrencyBaseline "-Pclients=10" "-Piterations=5" "-Pseed=15001" "-PpartySize=2" "-PholdDuration=PT5M" "-Ptimeout=PT10S" "-Pstrategy=PESSIMISTIC_WRITE_SLOT" "-PhostStorage=NVMe SSD: WD PC SN810 SDCPNRY-512G-1006" "-PcontainerLimits=Docker Desktop defaults with no explicit per-container limits" "-PnetworkCondition=local loopback and Docker bridge with no traffic shaping" "-Poutput=build/reports/experiments/issue-78/pessimistic.json"

## 고정 조건

| 항목 | 값 |
| --- | --- |
| Client / iteration | 10 / 5, 총 50 요청 |
| Seed | 15001 |
| Product path | keyless POST /api/v1/venues/{venueId}/reservations/holds |
| Fixture | tenant 1, Venue 1, Customer session 2, iteration별 새 Table·Slot 1 |
| Initial state | 각 Slot의 Reservation과 Allocation 0 |
| Product model | TABLE_X_SLOT, capacity 1, Allocation unit 1 |
| partySize | 2, Resource seatingCapacity eligibility에만 사용 |
| HOLD / request timeout | PT5M / PT10S |
| 동시 시작 | iteration마다 client 10개와 runner가 하나의 CyclicBarrier 사용 |
| 최종 검증 | workload 직후 하나의 verificationNow로 5개 Slot 재조회 |
| Warm-up | 별도 warm-up 없음 |

이 run은 Product의 현재 전략을 그대로 사용한다. HOLD transaction의 첫 database read에서
대상 SlotInventory row만 FOR UPDATE로 잠그고, lock 획득 뒤 effective capacity를 재평가한다.
stale write나 system failure에 대한 application retry는 없다.
