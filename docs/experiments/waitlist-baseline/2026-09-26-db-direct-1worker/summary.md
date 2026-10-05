# Waitlist DB direct 1-worker 기준선

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

상태: **Measured**, correctness oracle **PASS**. Issue #105의 비교 입력이며 transport 채택 결정은 아니다.

## 실행 근거

- 실행 revision: `7ddbf241b91c7ac5597c68476bed92701d4a821d`, `dirty=false`.
- Java `25.0.4.1+1-LTS`, Spring Boot `4.1.1`, Gradle `9.7.1`, MySQL `8.4.11`, `REPEATABLE-READ`.
- Windows 11, CPU `AMD64 Family 25 Model 68 Stepping 1, AuthenticAMD`, logical processors 16, host RAM 16,329,510,912 bytes.
- Docker VM 16 processors / RAM 7,910,817,792 bytes. Container CPU/memory limits는 0(명시적 제한 없음); image ID와 kernel/version은 manifest에 보존.
- MySQL buffer pool 128MiB, max connections 151, flush-log-at-commit=1, sync-binlog=1; Hikari maximum/minimumIdle=10, connection timeout=30s.
- seed `10501`, tenant 2, batch 2, worker 1, hot Slot 등록 clients 4와 public HOLD contenders 4, backlog inputs 6.

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

위 표는 `summary.json`의 phase별 raw 재계산 값을 반올림했다. drain 상한은 해당 phase의 첫 worker
시작 전에 조회가 완료된 마지막 snapshot에서 outstanding backlog가 확인된 경우에만 산출한다.
상한은 첫 worker cycle부터
그 phase의 **첫 drained snapshot 조회 종료**까지다. normal/hot의 후속 drain이나 마지막 commit을
뜻하지 않는다. idle은 이미 drained 상태이며 시작 전 backlog 관측이 없어 `summary.json`에서도
`drainObservedUpperBoundMs`와 `drainBoundOrigin`을 생략한다. idle cycle/query overhead는 business
drain latency가 아니다. worker window는 first-cycle start→last-cycle end이며 중간 command와
DB 조회 간격을 포함한다. claim rate는 시도 rate이고 API throughput, PROMOTED rate 또는 steady-state
business throughput이 아니다. duplicate-verification은 성능 phase가 아니다.

전체 command invocation 72건의 aggregate P50/P95/P99는 **23.86/69.47/97.67ms**다. 등록·Offer action·
System discovery·duplicate probe·business conflict가 섞인 진단값이며 개별 API의 latency로 사용하지 않는다.
public use-case 측정이므로 HTTP/auth transport 비용은 제외된다. cold finite local run 1회이며 warm-up,
saturation, production SLO 또는 Kafka/DB direct의 상대 우위를 주장하지 않는다.

## 재현 / 재계산

Java 25와 Docker가 준비된 `backend/`에서 새 output directory를 사용한다.

```powershell
.\gradlew.bat waitlistBaseline '-Pseed=10501' '-Poutput=build/reports/waitlist-baseline/new-run'
.\gradlew.bat waitlistBaseline '-Precalculate=build/reports/waitlist-baseline/new-run'
```

저장 run은 raw JSON에서 summary와 correspondence CSV를 재계산해 양쪽 동일성 검사 **PASS**.
PR #112의 idle drain 보정은 새 workload 실행이 아니라 보존된 raw의 deterministic 재계산이다.
당시 보정에서는 idle의 drain 관련 두 field만 제거했고 normal/hot/independent/backlog 및 다른
수치는 그대로였다. 원 workload 실행과 보정 계산기의 revision/provenance를 분리해 검증했다.
이 문서와 `WaitlistBaselineRunner` / `WaitlistBaselineEvidence`의 oracle 및 synthetic negative
regression은 유지한다. 과거 raw 동일 bytes는 Git history에서 확인할 수 있으며 새 실행의
재계산은 새 output을 사용한다.
Product가 생성하는 UUID와 thread scheduling은 seed로 고정되지 않는다. 다음 run에서 같은 seed여도
hot Slot winner, event 수와 latency가 달라질 수 있으며 원 identity와 결과는 해당 run raw가 권위다.
Kafka broker/relay/consumer, 다른 logical consumer fan-out, 장애/retention/cutover 측정은 후속 Issue 소유다.

전체 protocol과 oracle 범위: [waitlist-baseline.md](../../waitlist-baseline.md).
