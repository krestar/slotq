# #111 M5 transport 비교와 operations 통합 evidence

**진행 중인 draft다. #111과 M5는 완료되지 않았다.** 비교 15회와 canonical 재계산은 통과했지만,
통합 operations drill은 두 번 실행해 모두 실패했다. 마지막 lag 관측 수정 뒤 전체 drill은
재실행하지 않았다. 전체 Backend test/clean build와 cumulative Frontend 검증도 미실행이다.
실제 실행과 남은 gate는 [진행 기록](progress.json)을 따른다.

Default transport, Kafka의 adoption 범위와 repository/runtime 지위, DB direct의 장기 지위는
아직 결정하지 않았다. 새 Accepted ADR, ADR Register/README/roadmap 동기화와 M5 종료 대조는
대표 drill 및 최종 검증 이후에 수행한다. 현재 수치만으로 Kafka 채택·제거나 default 변경을
정당화하지 않는다.

## 비교 protocol

#105의 `WaitlistBaselineRunner` public Booking/Waitlist command trace와
`WaitlistBaselineEvidence`의 current-state/FIFO/capacity/receipt oracle을 사용한다.
M5 adapter는 Waitlist와 `operations.event-observation` 두 logical consumer의 원본 membership,
target, effect/receipt/DONE 및 transport/epoch authority를 추가로 대조한다.
서버 UUID와 DB tie-break는 seed로 고정하지 않으며 raw에 보존한다. Seed 10501의 client intent,
등록/취소/Offer accept·reject·expiry/input trace는 모든 profile에서 같다.

| 조건 | 공통 계약 |
| --- | --- |
| Business 경계 | 실제 M4 public use case, immutable MySQL event append, effective capacity·locking current read·FIFO 유지 |
| Consumer | Waitlist + operations observer, logical-consumer-scoped production DB claim/executor/receipt |
| Receipt | Waitlist 결과 `PROMOTED`와 정상 no-op 구분, observer projection와 DONE atomicity; event 수=Offer 수를 요구하지 않음 |
| DB | MySQL 8.4, RR, Hikari maximum/minimum 10, connection timeout 30s, 동일 schema/configuration |
| Retry | max attempts 5, lease 30s, effect timeout 10s, lock wait 5s, delays 1/5/30/120s; 별도 Kafka business retry 없음 |
| Telemetry | 같은 Micrometer registry/log·trace instrumentation, exporter disabled, 동일 bounded snapshot 및 resource sampling |
| Warm-up | 같은 전체 trace를 같은 JVM의 별도 schema에서 1회; measured schema/topic/group 새로 준비, warm-up 결과는 비교 raw에 포함하지 않음 |
| 반복/순서 | 5 profile 각각 3회; repeat 1/3은 정순, repeat 2는 역순; manifest에 profile/repetition/actual clock/source hash 기록 |

각 trace는 normal, hot Slot, 여러 independent Slot, steady load(독립 Slot 24회 request/drain),
burst/backlog(batch보다 큰 6개 입력), idle, duplicate verification을 구분한다.
`DB_DIRECT-1`/`KAFKA-1`은 consumer당 executor 1개, `*-3`은 consumer당 3개다.
Kafka는 실제 partition 3개, 독립 logical group 2개와 각 3 member의 assignment/end/committed next를
기록한다. 비교의 executor/relay/intake는 하나의 bounded JVM 안에서 explicit concurrent cycle을
실행한다. 독립 JVM restart/failure isolation은 별도의 fresh integrated drill이 검증한다.
**이 측정을 독립 process 6개 또는 distributed host의 처리량으로 표현하지 않는다.**

## Resource / 시간 정의

| 비교 | MySQL caps | Broker caps | Product/relay/consumer 실행 예산 |
| --- | --- | --- | --- |
| DB direct 공통 budget | 1.5 CPU / 1536 MiB | 없음 | 공통 JVM `-Xmx768m`, scoped workers 2 또는 6개 |
| Kafka 공통 budget | 1 CPU / 768 MiB | 0.5 CPU / 768 MiB | 같은 JVM `-Xmx768m`, relay + scoped intake/executors |
| Kafka extra allocation | 1 CPU / 768 MiB | 1.5 CPU / 1536 MiB | 같은 JVM `-Xmx768m`, consumer당 3개 |

공통 budget은 container cap 합계 1.5 CPU/1536 MiB와 동일 harness JVM이다. Windows JVM과
Docker VM 전체를 하나의 aggregate cgroup으로 제한한 실험은 아니다. 같은 physical host·disk·NAT를
공유하고 IOPS/네트워크 cap이 없으며 실제 host/VM CPU·RAM·storage, image ID, pool/isolation은
각 manifest가 authoritative하다. Broker 비용을 MySQL 또는 app 비용에서 제외하지 않는다.
Extra allocation은 **동일 budget 성능 순위에 합치지 않는다**.

Resource raw는 Docker CPU/memory/network/block I/O와 JVM CPU/heap, DB Questions/row-lock wait·time/
redo/bytes, performance_schema의 blocking table(append fence 포함), Hikari active/waiting/acquire,
broker topic·offsets storage bytes와 group/partition state를 보존한다. Docker BlockIO 0도 그대로
기록하며 physical SSD write 0으로 해석하지 않는다. Network는 container lifetime 누적 표본으로
startup/warm-up을 포함한다. JVM CPU delta와 heap 표본, container CPU 표본은 서로 다른 단위다.
짧은 lock/pool wait가 표본 사이에서 사라질 수 있어 표본 0을 wait 없음의 증명으로 사용하지 않는다.

Latency의 event 시각은 event transaction 내부 DB `recorded_at`, intake는 MySQL target handoff
`intaken_at`, effect는 receipt/DONE transaction의 timestamp다. Physical commit 시각이 아니다.
Public command return/post-cycle DB read와 demand→첫 Offer 관측은 upper bound다. DB/host clock
오차는 manifest의 bracket으로 남기고 process-local elapsed에는 `System.nanoTime`을 사용한다.
Business mutable clock은 registration 순서와 실제 Offer expiry fixture를 제어한다. DB lease와
Kafka/host clock을 business clock으로 대신하지 않는다.

Steady load는 closed-loop finite trace이며 handler/drain/snapshot 사이에 다음 입력을 기다린다.
Coordinated omission과 instrumented snapshot overhead를 포함하므로 saturated capacity, open-loop
production traffic 또는 production p99/SLO가 아니다. Backlog drain 분모는 pre-worker positive
backlog의 최초 worker start→최초 drained observation end다. Idle에는 이 drain bound가 없다.
Kafka durable intake rate/delay/lag, original membership backlog, materialized DB backlog와 DONE
rate를 각각 기록하며 `lag=0`을 effect completion으로 환산하지 않는다.

M4 [single-consumer baseline](../waitlist-baseline/2026-09-26-db-direct-1worker/summary.md)은 cold/no-warm-up,
Waitlist 1개 consumer의 역사적 참고값이다. M5의 two-consumer/steady extension trace와 동일
workload 또는 직접적인 상대 성능 분모로 사용하지 않는다.

## 재현과 canonical evidence

JDK 25 / Docker, `backend/`에서 fresh output을 사용한다. `hostStorage`에는 실행 host의 실제
volume/device와 IOPS/network 제한을 기입한다. Gradle cache를 사용할 때 `--offline`을 추가할 수 있다.

```powershell
./gradlew.bat m5TransportComparison '-Poutput=build/reports/m5/fresh' '-PhostStorage=<actual host storage/network>'
./gradlew.bat m5TransportComparison '-Precalculate=build/reports/m5/fresh'
./gradlew.bat m5CanonicalEvidence '-Pinput=build/reports/m5/fresh' '-Pexport=../docs/experiments/m5-transport/<fresh-cohort>'
./gradlew.bat m5CanonicalEvidence '-Precalculate=../docs/experiments/m5-transport/<fresh-cohort>'
./gradlew.bat m5OperationsDrill '-Poutput=build/reports/m5-drill/fresh'
./gradlew.bat m5OperationsDrill '-Precalculate=build/reports/m5-drill/fresh'
```

`M5CanonicalEvidence`는 동일 tenant row의 **version**을 SHA-256 dictionary에 한 번 저장하고
모든 observation 순서/시각과 row reference를 보존한다. Lossless round-trip을 검사하고, raw→summary와
#105 correspondence CSV 재생성 hash를 대조한다. Per-run CSV와 summary 복제본은 commit하지 않는다.
Canonical raw는 lossless gzip 한 형식(`raw.json.gz`)으로 보존하며 verifier가 직접 읽는다.
이 디렉터리의 `.gitattributes`는 JSON의 LF와 gzip binary를 고정해 Windows checkout의
자동 CRLF 변환이 manifest/summary byte hash를 바꾸지 않도록 한다.
압축되지 않은 JSON 복제본은 commit하지 않는다. Canonical verifier는 row/manifest/raw/root-summary hash, cohort inventory, 두 logical consumer,
seed, profile별 3회 이상 반복을 확인한다. Root summary와 integrity는 raw만으로 재계산 가능하다.

| 보존 파일 | 직접 충족하는 gate |
| --- | --- |
| 비교 run별 `manifest.json` | 실제 환경·workload·budget·시간·source provenance |
| 비교 run별 `raw.json.gz` | latency/backlog/resource와 두 consumer correctness의 재계산 source |
| 비교 root `summary.json`, `integrity.json` | 15 run의 최종 판정과 raw/CSV 재계산 hash |
| Drill `manifest.json` / `raw.json.gz` / `summary.json` / `integrity.json` | 대표 장애, actual alert/dashboard/trace, TLS human audit, durable convergence와 rollback |
| `intake-correction.json` | 실제 two-consumer cold-target deadlock 전/후 repeated regression 결과 |
| `progress.json` | 실행한 검증, 실패한 drill과 미실행 gate를 구분하는 진행 기록 |
| Harness·이 문서·draft 통합 runbook | 재현 명령과 아직 검증 중인 운영 절차; 최종 ADR은 미작성 |

Warm-up/exploratory 실패·중간 dump·전체 console/application/container log, argument/credential/TLS
파일과 동일 상태의 JSON/CSV 복제본은 로컬 ignored `build/`에 남긴다. 과거 main evidence와
그 source/hash/ADR 관계는 보존한다. `intake-correction.json`의 실패는 수정 전 race 재현이며
최종 비교 correctness PASS로 세지 않는다.

## 2026-10-01 실제 비교 결과

이 cohort는 empty-partition lag 관측 수정 **이전**에 실행됐다. 각 manifest의 실제 source hash를
보존하며 최신 변경 후 실행 결과로 재표기하지 않는다. 최종 판단 전 관측 수정의 영향과 필요한
재실행 범위를 확인해야 한다. Lossless gzip export와 재계산은 수정 후에도 성공했다.

[Canonical cohort](2026-10-01-comparison/summary.json),
[integrity](2026-10-01-comparison/integrity.json). `m5TransportComparison`은 27m59s에 성공했고
15 raw→summary/CSV 대조와 lossless canonical export가 성공했다. 매 run 41 original / 82 target,
Waitlist receipt 41 / observer receipt 41, Offer/notification 38, 정상 no-op 3이다.
전체 615 original / 1230 target에서 unexplained loss·duplicate logical effect·partial receipt/DONE·
effective capacity violation·unexpected DEAD/quarantine·missing membership/authority는 0이다.
비교에서는 automatic extra claim/retry가 0이며 fault/recovery는 아래 별도 drill의 실제 결과를 따른다.

각 셀은 **3 run의 해당 통계값 중앙값**이며 pooled percentile이 아니다. 모든 per-run 분포와
phase별 window/count/rate는 canonical root summary에 있다. Timing 단위 ms, rate 단위 original/s 또는
business target/s다. Command p95에는 같은 public trace의 정상 conflict와 lifecycle command를 포함한다.

| Profile | Command p95 | event→DONE p95 | event→intake p95 | demand→Offer 관측 p95 | steady input/s | steady DONE/s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| DB_DIRECT-1 | 27.29 | 712.82 | 해당 없음 | 3314.93 | 2.520 | 5.259 |
| KAFKA-1 | 32.33 | 2435.26 | 1864.76 | 11988.01 | 0.676 | 1.411 |
| DB_DIRECT-3 | 27.00 | 669.23 | 해당 없음 | 2716.42 | 2.523 | 5.266 |
| KAFKA-3 | 31.93 | 2374.05 | 1814.80 | 11985.28 | 0.671 | 1.400 |
| KAFKA-3-extra (추가 budget) | 29.10 | 2369.38 | 1820.94 | 12448.98 | 0.655 | 1.366 |

| Profile | backlog DONE/s | Waitlist drain upper bound ms | idle cycle 평균 ms | MySQL peak 표본 MiB 범위 | broker peak 표본 MiB 범위 |
| --- | ---: | ---: | ---: | ---: | ---: |
| DB_DIRECT-1 | 12.97 | 430.65 | 13.19 | 492.6–529.6 | 해당 없음 |
| KAFKA-1 | 3.28 | 2215.25 | 541.52 | 485.6–488.4 | 572.7–600.0 |
| DB_DIRECT-3 | 13.67 | 399.39 | 18.50 | 503.1–506.1 | 해당 없음 |
| KAFKA-3 | 3.33 | 2207.44 | 546.52 | 494.9–499.6 | 585.4–587.4 |
| KAFKA-3-extra (추가 budget) | 3.29 | 2264.79 | 546.43 | 495.1–496.9 | 570.7–578.3 |
| M4 참고: Waitlist 1 consumer / cold 1 worker | 6.49 (Waitlist만) | 609.65 | historical summary 참조 | 별도 manifest | 없음 |

M4 참고는 17 original/17 target/14 Offer로 **다른 workload**다. M5 backlog 첫 표본은 6 original의
12 outstanding membership target이며 마지막에는 0이다. 위 DONE/s는 두 consumer를 합친 값이고
drain upper bound는 #105 schema의 Waitlist 관측이다. Phase 첫/마지막 표본의 count 차이를 분모로
사용하므로 carry-in completion과 첫 표본 이전 input을 같은 interval의 입력으로 재분류하지 않는다.
Idle는 빈 cycle 비용이며 짧은 idle 구간의 broker background CPU를 정밀 분리한 측정은 아니다.

| Profile | MySQL CPU p95 표본 % 범위 | broker CPU p95 표본 % 범위 | broker 누적 RX / TX 범위 | 최종 topic+offset log bytes 범위 | DB Questions 증가 | row-lock waits / time ms 범위 |
| --- | ---: | ---: | --- | ---: | ---: | --- |
| DB_DIRECT-1 | 26.66–27.02 | 해당 없음 | 해당 없음 | 해당 없음 | 18065 | 47 / 388–443 |
| KAFKA-1 | 14.16–15.25 | 7.18–13.71 | 704–720 kB / 840–855 kB | 55407–55516 | 20517 | 46–47 / 463–513 |
| DB_DIRECT-3 | 28.95–30.88 | 해당 없음 | 해당 없음 | 해당 없음 | 21753–21779 | 363–367 / 2672–2781 |
| KAFKA-3 | 12.82–15.62 | 15.12–25.83 | 1000–1020 kB / 1220–1240 kB | 57897–58506 | 24225–24241 | 372–378 / 2746–3708 |
| KAFKA-3-extra (추가 budget) | 14.46–14.98 | 15.66–19.66 | 973–996 kB / 1190–1210 kB | 57937–58019 | 24221–24245 | 362–375 / 2949–3144 |

CPU는 Docker 한 CPU=100% 단위의 실제 finite-window 표본이다. Broker log bytes는 topic과
`__consumer_offsets`의 replica log size이며 전체 container filesystem/reserved storage가 아니다.
MySQL/JVM RAM과 container cap, heap 표본을 포함한 원 값은 root summary와 manifest를 따른다.
BlockIO는 모든 표본 `0B / 0B`로 보고됐고 storage I/O가 없었다는 결론을 내리지 않는다.
Hikari acquire max는 run별 1.055–2.309ms, boundary active/waiting와 append-fence blocking sample은
0이었다. 동시에 실제 cumulative row-lock wait가 존재하므로 sampling 한계를 숨기지 않는다.

두 group은 최종 lag 0이고 3-member profile의 각 member가 partition 1개씩을 소유했다. 하지만
최종 lag만으로 DB 완료를 판정하지 않고 별도 82 receipt/DONE을 검증했다. 이 finite closed-loop
trace에서는 1→3과 추가 broker budget의 처리량 이득이 작고 shared DB 경쟁/운영 비용은 늘었다.
이는 Kafka의 distributed scale-out 효용을 반증하지 않는다. 실제 지원 topology의 요구·isolation·
recovery·resource/운영 비용과 함께 adoption을 판단한다.

## 선행 gate 대조

Issue state/checkbox만으로 완료를 추론하지 않고 최신 main의 merge와 구현·원자료를 대조한다.
다음 표는 historical evidence의 출처이며 이번 #111의 fresh 비교/drill 또는 최종 test 실행과
구분한다. 기존 원자료와 hash는 수정하지 않는다.

| Issue / merge | 실제 구현·완료조건 근거 | #111에서 유지·통합하는 경계 |
| --- | --- | --- |
| #105 / PR #112, `9a934a5` | [baseline/protocol](../waitlist-baseline.md), 3회 raw·manifest·recalculation 및 schema/oracle | 같은 public trace와 시각·분모, normal no-op, consumer identity/membership, FIFO/current state·capacity |
| #106 / PR #115, `16ba6b6` | [DB dashboard/alert/trace](../product-observability/2026-09-27-db-direct/README.md), [blocker regression](../product-observability/2026-09-27-blocker-regression/README.md), 최소 UI request correlation | finite label/redaction, separate machine credential/read-only sampler, missing≠zero, collector outage의 transaction 독립성; 실제 Kafka 역할 scrape 통합 |
| #107 / PR #116, `de95fc2` | [relay](../kafka-relay/2026-09-28/README.md), [blocker correction](../kafka-relay/2026-09-28-blocker-regression/README.md) | MySQL append atomicity, committed relay claim 뒤 broker I/O, publication identity·ACK unknown·finite retries, canonical/ACL/retention guard |
| #108 / PR #119, `fd3510a` | [intake/vertical slice/browser](../kafka-consumer/2026-09-29/README.md), actual cutover/crash/scope tests | durable intake→offset→scoped executor handoff, exact original target/epoch, original FIFO/receipt/DONE, both manual directions; cold target concurrency correction |
| #109 / PR #120, `57cfd3c` | [process/broker fault matrix](../kafka-fault/2026-09-29/README.md), Sept30 candidate/slow-intake/maintenance/relay/cutover raw | independent JVM/rebalance, intake 전/후/offset crash, pending-first execution, retention/quarantine/stale owner reconciliation; matrix 전체 반복 대신 새 통합 대표 drill |
| #110 / PR #121, `7a38901` | [human recovery](../operator-recovery/2026-09-30/README.md), 53-case MySQL security/atomicity/unknown regression, operations CLI/runbook | actual personal credential + tenant/consumer/action grant, exact state/fence/epoch, idempotent operation·append-only audit, business replay와 publication recovery 분리 |

#108은 Closed이고 PR #119가 merge됐지만 Issue 본문 checkbox는 미체크 상태다. 이를 체크됐다고
보고하지 않는다. 위 실제 intake/vertical-slice/cutover evidence와 current regression을 기준으로
완료조건을 대조한다. #109 historical gate에는 당시 미실행 clean build가 명시돼 있으며 이를 PASS로
세지 않는다. #111의 전체 test/clean build는 아직 미실행이며 별도 gate로 남아 있다.

## Regression 기준

- Source/authority/oracle: committed original unexplained loss, duplicate logical effect, partial
  receipt/DONE, effective capacity violation, missing membership/assignment, unexplained DEAD/quarantine는
  허용 0이다. Current-read/FIFO, scope/fence, lease/finite retry, no-op 의미를 통과시켜야 한다.
- Intake/publication: current RR cold-target concurrency, canonical duplicate와 poison, durable prefix,
  first materialization, target attempts 불변, ACK unknown/redelivery, durable-intake 뒤 DB recovery,
  registration/cutover와 stale owner regression을 유지한다. Kafka lag 0을 oracle 대신 사용하지 않는다.
- Security/operations: machine/human/Product boundary, real credential expiry/revocation/grant, exact
  request·operation·audit atomicity/unknown, metrics label/redaction/cap/staleness regression을 유지한다.
- Performance: manifest/trace/oracle/budget/telemetry가 일치하는 새 3회 이상 cohort로 비교한다. 이
  finite instrumented baseline의 수치를 production SLO나 합격 절대값으로 고정하지 않는다. 같은
  조건에서 latency/backlog/resource 비용이 반복 악화되면 raw와 query/wait/partition 근거로 조사한다.
- Runtime/retention/protocol 변경은 대표 drill을 새로 실행한다. Routine low-impact 변경에는 #109
  전체 fault matrix를 반복하지 않는다. DB direct와 Kafka를 유지하는 동안 둘의 scoped execution,
  receipt/authority/manual cutover/operator path와 harness/version/runbook을 함께 유지한다.
