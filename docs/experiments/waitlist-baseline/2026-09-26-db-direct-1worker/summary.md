# Waitlist DB direct 1-worker 기준선

상태: **Measured**, correctness oracle **PASS**. Issue #105의 비교 입력이며 transport 채택 결정은 아니다.

## 실행 근거

- 실행 revision: `7ddbf241b91c7ac5597c68476bed92701d4a821d`, `dirty=false`.
- 원자료: [raw.json](raw.json), [manifest.json](manifest.json), 재계산 결과: [summary.json](summary.json), [correspondence.csv](correspondence.csv).
- Java `25.0.4.1+1-LTS`, Spring Boot `4.1.1`, Gradle `9.7.1`, MySQL `8.4.11`, `REPEATABLE-READ`.
- Windows 11, CPU `AMD64 Family 25 Model 68 Stepping 1, AuthenticAMD`, logical processors 16, host RAM 16,329,510,912 bytes.
- Docker VM 16 processors / RAM 7,910,817,792 bytes. Container CPU/memory limits는 0(명시적 제한 없음); image ID와 kernel/version은 manifest에 보존.
- MySQL buffer pool 128MiB, max connections 151, flush-log-at-commit=1, sync-binlog=1; Hikari maximum/minimumIdle=10, connection timeout=30s.
- seed `10501`, tenant 2, batch 2, worker 1, hot Slot 등록 clients 4와 public HOLD contenders 4, backlog inputs 6.
- Product/main 및 harness/build의 정확한 실행 bytes SHA-256 **256개**를 현재 source와 대조하여 모두 일치함을 확인.

자동 timer는 test-only scheduler에서 억제하고 기존 worker/public use case를 호출했다. 활성화·registration·handler·transaction·lock·receipt·DONE 구현은 그대로 사용했다.
고정 business clock과 DB UTC lease clock을 분리했고, elapsed window는 `System.nanoTime`, command/cycle/DB 조회 구간은 host UTC도 함께 남겼다.
DB−host clock sample 범위는 **−2..+2ms**이며 전체 실행의 clock drift 보장이 아니다. Context startup **11,651.61ms**는 workload cycle 수치에 포함하지 않았다.

## 실제 결과

| 집계 | 결과 |
| --- | ---: |
| committed original event / original membership target / DONE | 17 / 17 / 17 |
| completed logical receipt / Offer / notification request | 17 / 14 / 14 |
| PROMOTED / NO_CANDIDATE / NO_CAPACITY | 14 / 1 / 2 |
| undiscovered / outstanding / DEAD | 0 / 0 / 0 |
| lifetime attempts / retry attempts | 17 / 0 |
| 성공 command invocation / business conflict | 69 / 3 |
| duplicate real handler probes | 24 |
| fresh authoritative observations | 30 |

Oracle는 event·receipt 의미/불변성, original registration membership/cursor, tenant/scope,
partial effect/receipt/DONE, 중복 logical effect, Offer/Entry/Reservation/Allocation/notification 연결과
각 관측의 동일 `verificationNow`에서 effective capacity를 검증했다. 위반은 검출되지 않았다.
eligible FIFO, reject/expiry→next와 accept는 runner assertion을 통과했다. 과거 M4 evidence를 이번 측정값으로 재사용하지 않았다.

| Phase | cycle / claim | cycle P50 / P95 (ms) | 첫 drain 관측 상한 (ms) | worker window (ms) | claim attempts/s |
| --- | ---: | ---: | ---: | ---: | ---: |
| normal | 7 / 7 | 96.47 / 136.60 | 281.25 | 2716.52 | 2.58 |
| hot-slot | 2 / 2 | 88.75 / 111.19 | 178.05 | 374.67 | 5.34 |
| independent-slots | 1 / 2 | 160.13 / 160.13 | 292.84 | 160.13 | 12.49 |
| backlog | 3 / 6 | 141.89 / 143.06 | 609.65 | 484.56 | 12.38 |
| idle | 3 / 0 | 17.42 / 18.86 | 해당 없음 | 53.73 | 0 |

위 표는 `summary.json`의 phase별 raw 재계산 값을 반올림했다. drain 상한은 첫 worker cycle부터
그 phase의 **첫 drained snapshot 조회 종료**까지다. normal/hot의 후속 drain이나 마지막 commit을
뜻하지 않는다. idle은 이미 drained 상태라 raw의 해당 관측 간격은 query overhead이며 business
drain latency로 사용하지 않는다. worker window는 first-cycle start→last-cycle end이며 중간 command와
DB 조회 간격을 포함한다. claim rate는 시도 rate이고 API throughput, PROMOTED rate 또는 steady-state
business throughput이 아니다. duplicate-verification은 성능 phase가 아니다.

전체 command invocation 72건의 aggregate P50/P95/P99는 **23.86/69.47/97.67ms**다. 등록·Offer action·
System discovery·duplicate probe·business conflict가 섞인 진단값이며 개별 API의 latency로 사용하지 않는다.
public use-case 측정이므로 HTTP/auth transport 비용은 제외된다. cold finite local run 1회이며 warm-up,
saturation, production SLO 또는 Kafka/DB direct의 상대 우위를 주장하지 않는다.

## 재현 / 재계산

Java 25와 Docker가 준비된 `backend/`에서 새 output directory를 사용한다.

```powershell
.\gradlew.bat waitlistBaseline '-Pseed=10501' '-Poutput=../docs/experiments/waitlist-baseline/new-run'
.\gradlew.bat waitlistBaseline '-Precalculate=../docs/experiments/waitlist-baseline/2026-09-26-db-direct-1worker'
```

저장 run은 raw JSON에서 summary와 correspondence CSV를 재계산해 양쪽 동일성 검사 **PASS**.
Product가 생성하는 UUID와 thread scheduling은 seed로 고정되지 않는다. 다음 run에서 같은 seed여도
hot Slot winner, event 수와 latency가 달라질 수 있으며 원 identity와 결과는 해당 run raw가 권위다.
Kafka broker/relay/consumer, 다른 logical consumer fan-out, 장애/retention/cutover 측정은 후속 Issue 소유다.

전체 protocol과 oracle 범위: [waitlist-baseline.md](../../waitlist-baseline.md).
