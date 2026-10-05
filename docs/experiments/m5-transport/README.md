# #111 M5 transport 비교와 operations 통합 evidence

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

비교 15회와 fresh integrated operations drill이 PASS했다. 마지막 lag 관측 수정 이후 실제
broker/DB 장애, authorized human recovery와 quiesced rollback을 끝까지 실행했다.
관련 regression, 전체 Backend test/clean build와 cumulative Frontend 검증도 통과했다.
당시 실행 결과와 limitation은 이 summary, M5 종료 대조는 [closure](closure.md)를 따른다. Local 종료 gate는 PASS이며 main Complete는 PR #122 반영 이후다.

[Accepted ADR-0008](../../adr/0008-m5-event-transport-and-runtime-status.md)은 기본 transport를
`DB_DIRECT`로 유지하고, **별도로** Kafka implementation을 reproducible experimental/comparison
topology로 유지한다. DB direct는 permanent supported default/manual rollback target이며 Kafka의
상시 운영 adoption은 보류한다. 단순 latency 순위만으로 채택·제거를 결정하지 않았다.

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

JDK 25 / Docker가 필요하다. `backend/`에서 fresh output을 사용하고 `hostStorage`에는 실제
host storage/network 조건을 기록한다. 재계산/압축 export는 새로 생성한 raw를 입력으로 사용한다.

```powershell
./gradlew.bat m5TransportComparison '-Poutput=build/reports/m5/fresh' '-PhostStorage=<actual host storage/network>'
./gradlew.bat m5TransportComparison '-Precalculate=build/reports/m5/fresh'
./gradlew.bat m5CanonicalEvidence '-Pinput=build/reports/m5/fresh' '-Pexport=build/reports/m5/canonical-fresh'
./gradlew.bat m5CanonicalEvidence '-Precalculate=build/reports/m5/canonical-fresh'
./gradlew.bat m5OperationsDrill '-Poutput=build/reports/m5-drill/fresh'
./gradlew.bat m5OperationsDrill '-Precalculate=build/reports/m5-drill/fresh'
```

`M5CanonicalEvidence`의 lossless row dictionary, gzip round-trip, raw→summary/CSV 재계산과
새 cohort의 hash/inventory 검증은 유지한다. 이 검증은 local output의 무결성을 검사하며 Git
artifact 보존을 요구하지 않는다. `M5CanonicalEvidenceTests`, `M5TransportEvidenceTests`,
`WaitlistBaselineEvidenceTests`는 synthetic input으로 codec와 durable oracle의 거부 조건을 검증한다.
`M5OperationsDrillEvidenceTests`의 SQL failure/rollback/authority/audit 거부 및 기존 integrity bytes
보존 검증도 synthetic input과 TempDir로 실행한다. 실제 장애 재현은 `M5OperationsDrillRunner`와
`KafkaFaultProcessIntegrationTests`가 담당하며 synthetic test를 physical drill PASS로 세지 않는다.

## 2026-10-01 실제 비교 결과

비교 전에 두 consumer가 cold target을 동시에 만드는 RR 경합을 별도로 재현했다.
기준 `7a38901`에서 `KafkaIntakeConcurrencyIntegrationTests`의 세 반복이 실제
`MySQLTransactionRollbackException`으로 실패했고 보정 뒤 세 반복이 통과했다.
이 실패를 최종 비교 PASS에 합치지 않는다. 해당 actual MySQL concurrency regression은 유지한다.

이 cohort는 empty-partition lag 관측 수정 **이전**에 실행됐다. 당시 manifest의 source를
기준으로 하며 최신 변경 후 실행 결과로 재표기하지 않는다. Production source와 public trace/비교
runner/calculator hash를 직접 대조해 차이가 `KafkaIntakeObservability.java` 한 파일뿐임을 확인했다.
기존 broker end/committed 조회 결과의 빈 partition 판정만 바뀌었고 추가 broker/DB I/O나
append/intake/executor/receipt/retry/oracle은 바뀌지 않았다. 최신 positive/negative regression과
fresh broker outage/lag alert 해제를 별도로 검증했다. 기존 15회 측정을 다시 만들지 않았으며
lossless export와 15 raw→summary/CSV 재계산은 최신 verifier에서도 성공했다.

당시 `m5TransportComparison`은 27m59s에 성공했고 15 raw→summary/CSV 대조와 lossless export가
성공했다. 매 run 41 original / 82 target, Waitlist receipt 41 / observer receipt 41,
Offer/notification 38, 정상 no-op 3이다. 전체 615 original / 1230 target에서 unexplained loss,
duplicate logical effect, partial receipt/DONE, capacity violation, unexpected DEAD/quarantine와
missing membership/authority는 0이었다. Automatic extra claim/retry도 0이었다.

각 셀은 **3 run의 해당 통계값 중앙값**이며 pooled percentile이 아니다. 모든 per-run 분포와
phase별 window/count/rate의 당시 canonical bytes는 Git history에 남아 있다. Timing 단위 ms, rate 단위 original/s 또는
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
MySQL/JVM RAM과 container cap, heap 표본의 원 값은 당시 root summary/manifest에 기록했다.
BlockIO는 모든 표본 `0B / 0B`로 보고됐고 storage I/O가 없었다는 결론을 내리지 않는다.
Hikari acquire max는 run별 1.055–2.309ms, boundary active/waiting와 append-fence blocking sample은
0이었다. 동시에 실제 cumulative row-lock wait가 존재하므로 sampling 한계를 숨기지 않는다.

두 group은 최종 lag 0이고 3-member profile의 각 member가 partition 1개씩을 소유했다. 하지만
최종 lag만으로 DB 완료를 판정하지 않고 별도 82 receipt/DONE을 검증했다. 이 finite closed-loop
trace에서는 1→3과 추가 broker budget의 처리량 이득이 작고 shared DB 경쟁/운영 비용은 늘었다.
이는 Kafka의 distributed scale-out 효용을 반증하지 않는다. 실제 지원 topology의 요구·isolation·
recovery·resource/운영 비용과 함께 adoption을 판단한다.

## Fresh integrated operations drill

2026-10-01 재개 후 `m5OperationsDrill`을 새로 실행했다. 첫 시도는 실제 MySQL pause까지 진행했지만
production scheduler가 의도적으로 redacted log를 남겨 harness의 stack-text 검사에 실패했다.
Production log를 약화하지 않고 test-only child probe가 장애 중 실제 scoped
`EventDeliveryWorker.runCycle()`을 호출해 JDBC exception class와 entrypoint만 보존하도록 수정했다.
Startup/비SQL failure와 메시지 유출은 negative regression으로 제외한다. 그 뒤 **10m49s fresh 전체
실행과 raw→summary 검사가 PASS**했다. 과거 실패/부분 실행은 이 PASS에 합치지 않는다.

당시 drill의 checkout HEAD는 `114d2da`였고 실행 당시 미커밋 test harness가 포함됐다.
이후 verifier의 기존 integrity 파일 재작성만 제거했으며 판단 기준/summary는 바뀌지 않았다.
현재 tree에는 재현 harness와 아래 관찰을 남긴다. 과거 모든 timing/row를 현재 tree만으로
동일 bytes로 재계산할 수는 없으며, 새 실행은 fresh output과 별도 provenance가 필요하다.

| 대표 flow | 실제 탐지: incident→첫 firing (s) | incident→durable convergence 관측 상한 (s) | 확인한 책임 |
| --- | ---: | ---: | --- |
| Kafka observer 중단/restart | 12.322 | 32.704 | observer backlog 동안 Waitlist/Booking 진행, process 복구 후 두 receipt/DONE |
| Kafka Waitlist DEAD/cause 수정/human replay | 15.474 | 18.675 | observer/Booking 진행, exact operation retry→audit 1→receipt/DONE |
| Broker unavailable/restart | 71.061 | 131.639 | lag unknown alert, immutable append/Booking 유지, publication/intake/business 별도 수렴 |
| ACK 이후 ledger marking 전 relay halt | 42.074 | 62.800 | expired publication claim 탐지, 실제 physical redelivery, 동일 logical target/effect |
| Durable intake 뒤 실제 MySQL pause/unpause | 34.398 | 91.138 | outage 전 두 intake/Waitlist PROCESSING/observer DONE, scrape 200 unhealthy, 실제 JDBC failure, 복구 뒤 둘 DONE |
| Quiesced Kafka→DB rollback | 해당 없음 | 36.538 | 전체 writer/worker 정지, epoch 2→3, original/target/attempt/receipt 불변, DB 신규 workload |
| DB observer 중단/restart | 12.900 | 34.130 | Waitlist/Booking 진행, observer projection/receipt 회복 |
| DB Waitlist DEAD/cause 수정/human replay | 12.820 | 16.072 | observer/Booking 진행, exact operation retry→audit 1→receipt/DONE |

Incident/first firing/durable snapshot의 monotonic nanos 차이다. Summary의 `observedAfterMs`는 alert
대기 시작부터의 시간이며 위 incident 기준과 다르다. Convergence는 명시적 원인 수정/restart,
finite timeout/lease, operator와 polling 시간을 포함한다. HTTP operator admission→business convergence는
Kafka 2.869s/DB 2.865s였다. 생산 환경 detection/recovery SLA 또는 timeout 합격 기준이 아니다.
Raw에는 incident/recovery start/end, operator 요청/응답, firing/clearing 시각과 snapshot을 보존한다.

`SlotqExecutionRuntimeUnavailable`, `SlotqConsumerDeadDelivery`, `SlotqDeadDelivery`,
`SlotqKafkaLagUnavailable`, `SlotqPublicationClaimExpired`, `SlotqDatabaseSampleUnavailable`의
actual Prometheus firing/clear를 확인했다. Protected TLS machine scrape, human unauthorized 401,
isolated role의 Product/operator 404, Grafana panel query 및 Tempo origin/attempt trace도 확인했다.
DB 장애는 `CommunicationsException`과 실제 production-cycle entrypoint를 남겼다. Outage 동안 조회할
수 없는 DB state를 healthy zero로 대체하지 않고 outage 전/후 durable snapshot과 telemetry를 대조한다.

최종 original 9/target 18 DONE, Waitlist PROMOTED receipt/Offer/notification 각 9,
human operation/audit 각 2, DB_DIRECT epoch 3이다. Loss·duplicate
logical effect·partial receipt/DONE·capacity violation·remaining unexplained DEAD/quarantine는 0이고
original membership/authority, receipt 및 Product outcome을 대조했다. Drill은 fixture당 한 eligible
Waitlist entry이며 여러 후보 FIFO/current-state는 15회 비교와 관련 regression에서 별도로 확인한다.
Booking HTTP는 여섯 대표 outage/rollback 상황에서 모두 201이었다. 실패 예산을 바꾸지 않았다.

Rollback 뒤 DB flow에서 Product의 local execution/maintenance timer를 test adapter로 제어했다.
따라서 DB producer-only 또는 Product로부터 완전한 Waitlist process 격리를 입증하지 않는다.
이번 fresh 실행은 Kafka→DB만 수행했으며 필요 없는 재전환을 새 PASS로 세지 않는다. 양방향 계약과
stale authority는 merged #108/#109와 현재 cutover regression 근거로 유지한다. 미회복 escalation과
실제 지원 role/horizon은 [통합 runbook](../../runbooks/event-operations.md)과 ADR-0008을 따른다.

## 선행 gate 대조

Issue state/checkbox만으로 완료를 추론하지 않고 최신 main의 merge와 구현·원자료를 대조한다.
다음 표는 historical evidence의 출처이며 이번 #111의 fresh 비교/drill 또는 최종 test 실행과
구분한다. 과거 결과와 한계를 summary에 남기며 raw bytes는 Git history에 남아 있다.

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
세지 않는다. 당시 cumulative 검증은 아래 결과로 별도 기록한다.

## Regression 기준

최종 관련 12 suite/94 case와 15 dataset 재계산 PASS, Backend 전체 `test` PASS(17m52s),
`clean build` 및 fresh drill 재계산 PASS(17m58s)다. Clean build JUnit은 84 suite/691 case 중
682 PASS, 9 existing opt-in skip, failure/error 0이다. Historical fault/security/diagnostic fixture
property가 필요한 skip을 새 PASS로 세지 않는다. Frontend typecheck/test(12 file/210 case)/build도
PASS했다. 당시 skip 9개는 capacity-lock 진단 3개와 외부 Kafka secure/fault/integrity fixture 6개였다.

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
