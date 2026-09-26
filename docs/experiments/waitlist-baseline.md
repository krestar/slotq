# 실제 Waitlist workload와 M5 전달 protocol 기준선

Issue [#105](https://github.com/krestar/slotq/issues/105)의 기준선이다. 현재 실행 대상은
M4의 실제 Booking→Waitlist 경로와 **DB direct mode, 1-worker**다. Kafka topology는 후속
#107/#108이 구현·검증할 확정 protocol이며, 이 문서가 Kafka를 기본 transport로 채택하거나
현재 Accepted ADR을 대체하지 않는다. production traffic에서 독립 consumer나 fan-out 문제가
이미 관측됐다는 주장도 하지 않는다.

실측 원자료와 결과: [2026-09-26 DB direct 1-worker run](waitlist-baseline/2026-09-26-db-direct-1worker/summary.md).

## 실제 Product 경로와 보존 경계

```text
public Waitlist 등록 → WAITING Entry
  → ordinary Booking capacity release 또는 available Slot의 promotion request admission
  → immutable MySQL event
  → original registration interval의 delivery target
  → claim / lease / fencing
  → current-state guard / eligible FIFO / promotional Booking HOLD
  → Offer / Entry / notification request / receipt / DONE 한 transaction
  → public Offer accept 또는 reject / System expiry
  → 실제 capacity release → 다음 후보
```

fixture는 Tenant/Venue/Resource/Policy/Slot과 인증 context만 준비할 수 있다. Reservation은
public Booking command, Entry는 public 등록으로 만든다. Offer, receipt, delivery DONE 또는
business effect를 직접 seed해서 정상 lifecycle을 건너뛰지 않는다. notification은 DB의
`OFFER_AVAILABLE` 요청이며 실제 외부 전송 또는 고객 수신의 증거가 아니다.

| 현재 계약 | 직접 확인한 구현 / 문서 | #105에서 보존할 의미 |
| --- | --- | --- |
| Product ownership / module 경계 | ADR-0002, Booking·Waitlist public port, integration adapter | 다른 module의 entity/repository를 Product 구현에서 공유하지 않음 |
| business/event atomicity | `ReservationTransitionRecorder`, `EventAppendService` | actual Allocation active→inactive와 event append가 같은 물리 transaction; append MANDATORY/rollback-only 유지 |
| Slot-first / 시간 | ADR-0006, #88, `PromotionalReservationService` | 대상 Slot 직렬화, commandNow 한 번 캡처, 동일 시각 effective capacity≤1 |
| current-read / FIFO | #95/#96, `JdbcPromotionalReservationContextQuery`, eligible Entry query | early RR snapshot에 의존하지 않음; scoped current context·NOWAIT·eligible `(joined_at,id)` 순서 유지 |
| receipt / no-op | `WaitlistPromotionService`, `JdbcWaitlistPromotionReceiptStore` | immutable 의미 검증 후 한 logical event의 완료 결과 재사용 |
| request admission | `WaitlistPromotionRequestAdmission` | receipt 없는 기존 request는 DEAD도 OUTSTANDING; 새 ID로 retry 우회 금지 |
| target execution | `EventDeliveryWorker`, `JdbcEventDeliveryStore` | claim commit부터 시도 소모; effect/receipt/DONE 한 commit; token/lease 재검사 |
| replay | `EventReplayService`, `event_replay_audit` | scoped DEAD만 trusted replay; 원 event/registration/receipt/lifetime 보존 |

ordinary release는 실제 저장 전이만 event를 만들며 GET, confirm, effective expiry 관측,
same-target 반복은 새 release가 아니다. release event는 capacity delta나 무조건 승급 명령이
아니라 current state 재평가 신호다. `PROMOTED`만 Offer를 만든다. `NO_CAPACITY`,
`NO_CANDIDATE`, `NOT_ELIGIBLE`, `SLOT_PAST`, `DEFERRED`는 정상 완료 no-op이며 각각 receipt와
DONE을 commit한다. DB/constraint/flush/fencing 오류를 no-op로 기록하지 않는다.

completed no-op 뒤 새 수요나 가용성이 생겨도 원 event의 결과는 바뀌지 않는다. 현재 기회를
새로 관측한 request admission만 새로운 event를 발급한다. Request의 `last_event_id`는 완료
ledger가 아니며 완료 oracle은 원 event와 의미가 일치하는 정상 receipt다.

## 재현 방법과 고정 workload

Java 25와 실행 중인 Docker가 필요하다. Backend directory에서 실행한다. 매 실행은 disposable
`mysql:8.4`에 현재 Flyway migration을 적용하며 `REPEATABLE-READ`를 확인한다. 아래 output은
존재하지 않는 새 directory로 지정한다. 저장된 evidence를 덮어쓰거나 과거 recovery 파일을
이번 baseline 결과로 재사용하지 않는다.

```powershell
cd C:\dev\slotq\backend
.\gradlew.bat waitlistBaseline `
  "-Pseed=10501" `
  "-Poutput=../docs/experiments/waitlist-baseline/<new-run-id>"

.\gradlew.bat waitlistBaseline `
  "-Precalculate=../docs/experiments/waitlist-baseline/<new-run-id>"
```

focused correctness 및 기존 M4/Event regression의 진입점은 다음과 같다. 실제 성공 여부는
해당 실행의 test report로 별도 확인한다.

```powershell
.\gradlew.bat test --tests '*WaitlistBaselineEvidenceTests' --tests '*WaitlistBaselineSmokeTests'
.\gradlew.bat test --tests '*WaitlistPromotionDeliveryIntegrationTests' --tests '*WaitlistPromotionRequestIntegrationTests' --tests '*WaitlistMaintenanceIntegrationTests' --tests '*WaitlistRegistrationIntegrationTests' --tests '*BookingCapacityReleaseIntegrationTests' --tests '*EventDeliveryIntegrationTests'
.\gradlew.bat test
.\gradlew.bat clean build
```

`<new-run-id>`는 실제 directory 이름으로 바꾼다. 비교 run은 같은 revision/source hashes,
host/Docker/DB 설정과 seed를 유지한다. 재계산은 기존 raw를 읽어 summary와 CSV가 동일한지
검증하며 새 MySQL workload를 실행하지 않는다.

[WaitlistBaselineRunner](../../backend/src/test/java/com/slotq/experiments/waitlist/WaitlistBaselineRunner.java)는
test classpath에만 있다. public `ReservationUseCase`, `WaitlistUseCase`, `WaitlistOfferUseCase`와
production request discovery/admission 및 `EventDeliveryWorker.runCycle()`을 호출한다. HTTP
serialization/authentication/network 비용은 측정하지 않는다. 인증 principal은 public provisioning으로
준비한 synthetic customer이며 Product command의 기존 권한 검사는 그대로 실행한다.

| Phase | 고정 workload | 구별할 관측 |
| --- | --- | --- |
| normal | 두 tenant; earlier ineligible demand 뒤 eligible FIFO; ordinary HOLD/confirm/cancel→promotion→reject→next→accept; release-free discovery→promotion→expiry→next→accept; no-demand release; admission 뒤 새 HOLD가 먼저 capacity를 차지한 delayed request→NO_CAPACITY→실제 release→promotion | 실제 두 v1 route, PROMOTED와 NO_CANDIDATE/NO_CAPACITY, Offer lifecycle, tenant scope |
| hot-slot | 같은 Table Slot의 HOLD 중 barrier의 4 clients가 대기 등록; 실제 release 뒤 하나의 delivery worker와 4 public HOLD clients가 같은 Slot에서 barrier 경합; HOLD가 이기면 실제 winner release 후 promotion, FIFO winner accept | 등록·capacity 경합; authoritative CAPACITY_UNAVAILABLE와 정상 NO_CAPACITY 구별; promotion worker는 하나 |
| independent-slots | 두 tenant의 독립 Slot request를 함께 enqueue한 뒤 drain | 비경합 Slot control; 실행 worker는 여전히 하나 |
| backlog | 두 tenant에 번갈아 6개 독립 Slot request를 먼저 commit, delivery batch=2로 drain | backlog > batch, 미물질화→PENDING→DONE 관측 |
| idle | backlog drain 뒤 빈 worker cycle 3회 | idle scan 비용과 claimed=0 |
| duplicate-verification | 모든 phase 완료 후 모든 원 event를 joined real handler에 재전달, 원 Product tables 전후 동일성 및 DONE 비claim 확인 | 완료 no-op 포함 receipt 재사용의 correctness diagnostic; performance phase 아님 |

Table × Slot capacity=1, Allocation unit=1이며 partySize는 Resource seating eligibility다.
seed는 client principal ID와 registration key의 생성 순서를 고정한다. Product의 server event/
Entry/Offer/Reservation ID는 실제 public path가 발급하고 raw에 보존한다. seed가 thread scheduling,
서버 UUID 또는 동시 등록 tie-break 순서를 고정한다는 뜻은 아니다. hot-slot의 기대 FIFO는 실제
MySQL `(joined_at,id)` 순서에서 구하며 thread index를 FIFO로 가정하지 않는다.
worker와 public HOLD의 capacity winner도 scheduling에 따라 달라진다. HOLD가 승리한 run과
promotion이 승리한 run의 outcome 수를 같다고 가정하지 않고 실제 raw를 보존한다.

두 exact production route의 bootstrap/readiness를 그대로 사용한다. promotion/maintenance/delivery
flags는 모두 enabled지만 **test scheduler만 timer 등록을 억제**한다. workload가 discovery,
expiry와 단일 synchronous worker cycle을 명시적으로 호출한다. poll interval이 실제 cycle 간격을
결정하는 scheduler throughput 실험이 아니다. worker lease=30s, effect timeout=10s, lock wait=5s,
max attempts=5, retry delays=1s/5s/30s/2m, Hikari maximum pool=10을 manifest에 기록한다.

business time은 test-only mutable UTC Clock의 고정 BASE에서 시작해 순차 등록마다 1μs를
전진시키고 실제 Offer expiresAt으로 이동한다. claim/lease/fencing에는 실제 MySQL UTC를 쓴다.
business time 차이를 elapsed latency로 계산하지 않는다. BASE는 fixture의 미래 Slot과 유효한
영업시간을 반복 생성하기 위한 값이며 실행일의 production traffic 재현이 아니다.

normal phase와 마지막 `duplicate-verification`의 `duplicate-handler-probe`는 완료된 원 event를
같은 `DeliveryTransactions` 안에서 실제 exact handler에 다시 전달하여 receipt/effect가 재사용되는지
검사한다. 마지막 진단은 완료 no-op를 포함한 모든 event를 대조한다. DONE target이
claim되지 않고 모든 원 table snapshot이 동일함을 함께 확인한다. 새로운 delivery state를 직접
seed/reset하는 probe가 아니며 신규 target throughput 또는 Kafka redelivery 측정에도 넣지 않는다.
cycle latency/claimed rate와 command latency를 분리하고 probe를 포함한 mixed command 분포를
Product API의 대표 latency로 해석하지 않는다.

## Evidence 파일과 재계산 계약

각 run은 다음 네 파일을 보존한다. 실제 실행 뒤의 revision/dirty, 환경과 결과는 해당 run의
manifest와 summary가 근거다. 이 문서에는 실행 전 예상 결과 수치나 transport 우열을 기록하지 않는다.

| 파일 | Schema / 내용 | 재계산 역할 |
| --- | --- | --- |
| `manifest.json` | `raw.manifest`의 동일 사본 | revision/dirty/status, sourceSha256, runtime/DB/host/container/pool/workload/time |
| `raw.json` | `slotq-waitlist-baseline/v1` | manifest + commands + cycles + authoritative observations |
| `summary.json` | `slotq-waitlist-baseline-summary/v1` | raw oracle, finalCounts, command distribution, phase window/claimed/DONE/drain bounds |
| `correspondence.csv` | 최종 snapshot의 eligible original event×registration 한 행 | 원 event→target→receipt→Offer/Entry/Reservation/notification 대응; 미발견은 UNDISCOVERED |

`sourceSha256`는 `src/main/**`, Waitlist experiment test sources와 `build.gradle`의 **실제 bytes**를
SHA-256으로 기록한다. revision이 같아도 dirty source와 line ending이 다르면 hash가 다를 수 있다.
manifest에는 Java/Spring Boot/Gradle, OS/CPU/core/RAM, Docker version/VM core/RAM/kernel,
실제 MySQL image ID와 version/isolation/buffer pool/max connections/durability/timezone, container
memory/CPU 설정과 Hikari 설정을 남긴다. container limit=0은 해당 명시적 제한이 없다는 뜻이며
host 전체 자원을 반드시 사용할 수 있다는 보장이 아니다.

| Raw collection | 필드 / source | 해석 |
| --- | --- | --- |
| `commands[]` | phase, type, startNanos/endNanos, businessNow, committed, result | public command의 process-local elapsed window와 반환값; businessNow는 호출 직전 harness 관측이며 내부 commandNow의 별도 직접 계측 아님 |
| `cycles[]` | phase, startNanos/endNanos, claimed | materialize + candidate scan + synchronous execution을 포함한 실제 runCycle window; claimed는 attempt 수 |
| `observations[]` | phase, startNanos/endNanos, hostStartAt/hostEndAt, verificationNow, tenants[] | 조회 전후 monotonic·host UTC 구간; 동일 verificationNow로 각 tenant fresh read-only DB snapshot |
| `tenants[]` | tenantId, businessNow, databaseNow, 원 table rows, registrations, eventBoundary/discoveryCursor, stats | scoped business/event/target/receipt evidence; registration history는 global 원 membership |

경합 loser의 public HOLD는 `committed=false`, `result=CAPACITY_UNAVAILABLE`로 기록한다.
정상 business conflict를 system failure나 Waitlist receipt의 NO_CAPACITY와 혼합하지 않는다.
예상하지 않은 runtime/DB 오류는 기록 후 전파하며 정상 baseline 완료로 숨기지 않는다.

원 table rows는 event_records/event_deliveries/waitlist_promotion_requests/
waitlist_promotion_receipts/reservations/capacity_allocations/waitlist_entries/waitlist_offers/
waitlist_notification_requests/slot_inventories/waitlist_demands다. UUID BINARY(16)은 hex로, timestamp는
UTC로 남긴다. fixture UUID 외 credential/secret/customer 개인정보/HTTP 원문 payload를 기록하지 않는다.
Kafka publication destination, broker ack, offset/intake provenance는 이 DB direct run에서 측정하지
않는다. 후속 Kafka evidence는 원 event/registration/logical receipt identity와 아래 시각·분모의
의미를 유지하면서 transport별 provenance를 별도로 기록해야 한다.

[WaitlistBaselineEvidence](../../backend/src/test/java/com/slotq/experiments/waitlist/WaitlistBaselineEvidence.java)는
raw 원 rows로 membership/count/oracle/percentile/rate와 CSV를 다시 계산한다. latency quantile은
nearest-rank다. `finalCounts.originalEvents`와 `committedOriginalEventInputs`가 committed original event 분모다. `successfulCommandInvocations`는
성공 반환된 `commands[].committed`의 수이며 등록/Offer action/probe를 포함하므로 event 수와
다르다. `doneTargetsAdded`와 `observedDoneTargetsPerSecond`는 phase의 first→last snapshot에서
target DONE 증가분이다. receipt outcome 수가 business 완료 분모이고 `claimedAttemptsPerSecond`는
시도 rate다. 이 값을 모두 같은 throughput으로 표기하지 않는다.

`retryAttempts`는 target별 `max(lifetime_attempts-1,0)`의 합, `retriedTargets`는 lifetime attempts가
1보다 큰 target 수다. 후속 replay run에서는 이 값에 새 authorized cycle의 claim도 포함될 수
있으므로 automatic retry 횟수로 해석하지 않는다. cycle attempts와 replay audit를 따로 대조한다.
이번 기준선은 replay가 없고 두 값 모두 0이다.

`drainObservedUpperBoundMs`는 첫 worker cycle 시작부터 drained snapshot 조회 끝까지의 상한이다.
phase의 모든 command 생성 시간이나 마지막 effect의 정확한 commit latency가 아니다. raw의
process-local start/end window를 보존하고 startupMillis는 별도로 남긴다. warm-up 없이 실행하는
작은 유한 cold workload이므로 steady-state throughput, saturation, production capacity/SLO 또는
Kafka와의 성능 우위를 주장하지 않는다. 독립 Slot/control과 idle의 값도 같은 제한 안에서 해석한다.

## 세 identity와 authority

| 개념 | 논리 identity | 권위 / 중복 판정 | identity가 아닌 것 |
| --- | --- | --- | --- |
| Kafka publication | tenant scope의 original event × Kafka destination | committed immutable MySQL event와 해당 destination의 publication 책임 | group, offset, publish attempt |
| delivery target | original event × original registration | 원 registration ID/interval과 exact route; 현재 DB PK는 tenant/event/registration, target unique는 registration/event | 현재 active registration, 새 generation, worker replica |
| business effect receipt | tenant × logical consumer × original event | `waitlist.promotion` receipt의 immutable 의미와 기존 business unique/FK constraint | registration, group, offset, delivery attempt/token |

한 event가 여러 original registration의 target을 가져도 같은 logical consumer의 effect는
하나다. receipt 결과 재사용과 target DONE은 다른 단위다. original event ID를 재발급하거나
receipt namespace를 registration/group/offset으로 바꿔 redelivery를 새 effect로 만들지 않는다.
tenant는 event와 authoritative Product ownership에서 확인한다. transport metadata나 caller의
tenant 문자열이 권한 또는 business state를 대신하지 않는다.

| 책임 | DB direct baseline | 후속 Kafka mode의 확정 protocol |
| --- | --- | --- |
| Product commit / immutable source | 같은 MySQL business transaction | 동일; broker transaction으로 대체하지 않음 |
| target 책임 인계 | DB discovery가 원 membership으로 target materialize | Kafka intake가 원 membership/authority 검증 후 target + intake provenance를 MySQL commit |
| 실행 / 복구 | DB worker claim/lease/fencing | consumer-scoped DB executor claim/lease/fencing |
| business 완료 | effect + receipt + DONE atomic commit | 동일 |
| 실행 authority | DB direct | durable Kafka authority에 속한 target만 Kafka intake/executor가 소유 |

Kafka mode에서 기존 DB direct discovery가 신규 target을 우회 생성하거나 실행하면 안 된다.
Kafka intake 이후 DB ledger를 execution/recovery에 사용하는 것은 위임된 책임 수행이다.
dual authority는 동일 target을 서로 다른 transport 경로가 임의로 생성/실행하는 경우다.
broker 장애에 따른 automatic DB fallback은 허용하지 않는다.

## Durable intake → offset → executor

```text
Kafka record
  → tenant / immutable original event / original registration / transport authority 검증
  → delivery target + intake provenance MySQL commit
  → partition의 연속 durable-intake prefix까지 offset commit
  → consumer-scoped DB executor가 PENDING부터 발견
  → claim / lease / fencing
  → current business effect + receipt + DONE atomic commit
```

offset은 durable intake의 책임 인계 진행 위치다. business DONE을 의미하지 않는다. record를
먼저 intake한 후 asynchronous business effect가 아직 시작되지 않아도 offset은 연속 prefix까지
전진할 수 있다. 앞 record의 intake가 실패했으면 뒤 record가 성공했더라도 그 gap을 넘겨
offset을 commit하지 않는다. Poison/authority 오류를 silent skip하여 연속 prefix로 위조하지 않는다.
intake provenance의 실제 schema와 오류 처리 구현은 #108의 소유다.

| crash / 관측 | 복구 입력과 허용 동작 | 금지 동작 |
| --- | --- | --- |
| intake commit 전 | offset 미전진; Kafka redelivery로 검증/인계 재시도 | 메모리 처리만으로 offset 진행 |
| intake commit 후 offset commit 전 | 같은 original target/provenance 확인·idempotent 재사용 | attempts/DEAD/receipt reset |
| offset commit 후 최초 effect 전 | attempts=0 PENDING도 scoped DB executor가 발견·claim | Kafka redelivery가 없다는 이유로 target 누락 |
| claim 후 effect 전 | 기존 MySQL lease/token과 유한 attempt budget으로 reclaim | 새 target/event 발급 |
| effect commit outcome unknown | fresh DB receipt/DONE/lease로 재판정 | rollback 또는 성공 추측, receipt 먼저 독립 commit |
| effect confirmed rollback | 기존 transient 분류와 bounded retry, budget 소진 시 DEAD | 무한 retry, Product HTTP command 자동 retry |
| business DEAD | intake/offset 완료 유지; 원인 해결 후 authorized replay만 새 cycle | offset rewind 또는 새 identity로 DEAD 우회 |
| stale owner | token/state/lease가 맞지 않으면 effect/DONE/failure commit 불가 | old owner가 새 owner state 덮어쓰기 |

현재 DB worker의 state 전이는 다음과 같다. Kafka intake는 이 executor state machine을
대체하지 않으며 최초 target 생성 책임만 바꾼다.

| 전 상태 / 조건 | 후 상태 | 보존값 / 증가값 |
| --- | --- | --- |
| committed event의 합법 target materialize | PENDING | original event/registration, attempts=0 |
| due PENDING / expired PROCESSING, 예산 남음 | PROCESSING | cycle/lifetime attempts+1, token+1, fresh DB UTC lease |
| 유효 owner 정상 PROMOTED 또는 no-op | DONE | effect/완료 receipt/DONE 한 commit |
| 확정 rollback + transient, 예산 남음 | PENDING | nextAttemptAt, 원 identity/receipt 유지 |
| nonretryable / exhaustion | DEAD | stable failure code, attempts/lifetime 유지 |
| scoped authorized DEAD replay | PENDING | cycle attempts=0, token+1, audit append; lifetime/event/receipt 유지 |

## Registration / discovery / ordering / retention / 전환

original registration은 global exact `(consumer,eventType,schemaVersion)` history다. event의
tenant가 target tenant를 결정한다. membership은
`activationBoundary < event.boundarySequence < deactivationBoundary`이며 upper boundary가
null이면 상한이 없다. 비활성 registration의 미발견/PENDING/PROCESSING/DEAD도 원래 책임이
남는다. 재활성화는 새 registration ID를 만들지만 과거 target의 provenance를 바꾸지 않는다.
현재 active registration 또는 group 시작 offset으로 과거 membership을 재구성하지 않는다.

현재 `event_discovery` singleton은 **모든** original registration의 targets를 생성하는 global
scan cursor다. 한 transaction에서 materialize와 cursor advance를 함께 한다. boundary sequence는
append/registration cutover 및 discovery metadata이며 business FIFO/transport ordering이 아니다.
후속 role 분리에서 동일 singleton을 consumer 필터로 읽고 advance하면 다른 consumer target을
건너뛸 수 있다. global discovery가 모든 membership을 보존한 후 scoped executor가 실행하거나,
별도 scope의 discovery 진행 의미를 명시해야 한다. 구체적인 cursor/schema 변경은 #107/#108에서
구현·검증하며 singleton, publication cursor, Kafka intake offset을 같은 값으로 취급하지 않는다.

Kafka partition ordering은 intake와 연속 offset의 범위다. Waitlist의 eligible FIFO는 MySQL의
current Entry `(joined_at,id)` 순서이며 서로 다른 target의 effect 완료 순서를 보장하지 않는다.
replica 수와 partition 수가 Product FIFO/current-state/capacity authority를 바꾸지 않는다.
Waitlist promotion과 후속 운영 관측 logical consumer는 독립 subscription/backlog/failure를
가져야 한다. 같은 logical consumer replicas는 같은 receipt identity와 scoped executor 책임을
공유한다. Booking API 재시작 없이 consumer stop/restart/scale이 가능해야 하며 Kafka relay 또는
consumer 중단 중에도 committed MySQL event는 복구 입력으로 남아야 한다. DB direct 비교에서도
가능한 runtime role 분리와 동일 logical consumer workload를 적용한다.

retention horizon 이탈, committed offset < log-start offset, topic 재생성을 탐지하여 명시적인
복구 판단으로 넘긴다. silent latest reset은 허용하지 않는다. 필요한 event/receipt/registration/
delivery/audit 참조와 dedupe 근거를 자동 삭제하지 않는다. 보존 기간의 수치 및 deletion 순서는
이 baseline에서 선결정하지 않는다.

Kafka↔DB 전환은 quiesce, durable authority, original target inventory와 rollback 계약을 필요로
한다. inventory는 원 membership을 기준으로 미물질화와 PENDING/PROCESSING/DEAD/DONE을
대조할 수 있어야 한다. 전환을 이유로 attempts/receipt/original registration을 초기화하지 않는다.
각 단계의 source→destination authority와 rollback을 내구적으로 설명해야 하며 두 경로의
동시 실행이나 자동 fallback을 안전한 전환으로 간주하지 않는다. 실제 cutover/fault matrix는
#108/#109의 소유이며 #105는 production transport를 전환하지 않는다.

## 측정 시각과 분모

| 용어 | 의미 / source | 해석 제한 |
| --- | --- | --- |
| occurredAt | public command 또는 request admission의 캡처된 business clock, UTC microsecond 정규화 | commit 시각 아님; lock wait 전에 캡처 가능 |
| recordedAt | event INSERT에서 MySQL UTC timestamp | transaction commit 시각 아님 |
| publish-ack observed | broker ack를 caller가 관측한 시각 | DB commit 또는 consumer intake 아님; DB mode 미측정 |
| durable intake observed | target/provenance commit 후 fresh DB 조회에서 보인 구간 | 정확한 commit timestamp가 없으면 관측 구간/상한 |
| effect commit observed | 완료 receipt/DONE을 fresh DB 조회에서 보인 구간 | delivery updatedAt은 commit timestamp 아님 |
| startup | process/context 시작→ready 관측 구간 | steady-state effect latency에 섞지 않음 |
| verificationNow | 검증 phase에서 한 번 캡처한 business instant | 모든 Slot effective occupancy에 같은 값 사용 |
| duration | 동일 process의 monotonic timer 차이 | 다른 clock의 absolute timestamp 차이와 구별 |

관측 query 전후의 wall clock interval을 남기고 commit의 직접 계측이 없으면 정확한 commit
시각으로 표기하지 않는다. DB UTC sampling은 caller query 시작/끝 구간과 함께 보존한다.
DB clock과 host clock 비교는 네트워크/처리 지연을 포함한 관측이며 이 sample만으로 동기화
오차의 절대 최대치를 증명하지 않는다. latency 이름에는 시작/끝 source와 observed/bounded
의미를 포함한다. M4 recovery 시간은 JVM 기동·lease·관측 window를 포함하므로 performance
baseline 숫자로 전용하지 않는다.

manifest의 `dbMinusHostOffsetLowerMillis`/`dbMinusHostOffsetUpperMillis`는 한 DB UTC query를
둘러싼 hostBeforeMillis/hostAfterMillis로 계산한 DB−host offset 관측 범위다. millisecond 변환
rounding 여유 ±1ms를 포함하며 실행 전체의 clock drift, skew 또는 정확한 동기화 오차를 뜻하지
않는다. fixed business clock을 이 실제 clock 비교에 포함하지 않는다.

| 분모 / 집계 | 계산 단위 | 구별할 값 |
| --- | --- | --- |
| committed input | original event | 발생 command 수, target 수와 다름 |
| eligible target inventory | original event × original registration | 미물질화와 materialized 분리 |
| durable intake | original target의 DB 책임 인계 | DB direct materialization / Kafka intake 구별 |
| business completion | tenant × logical consumer × original event 완료 receipt | PROMOTED와 각 no-op 별도 |
| target completion | DONE target | receipt 완료 수와 다를 수 있음 |
| outstanding business backlog | eligible logical event 중 완료 receipt 없음 | 미물질화/PENDING/PROCESSING/retry 대기/DEAD별 이유 |
| retry | target lifetime attempts와 cycle attempts | replica/redelivery 수를 business 실패 수로 섞지 않음 |
| DEAD | terminal target | 정상 no-op, 성공, 유실에 섞지 않음 |
| Kafka lag | partition offset/log-end 진행 차이 | DB effect backlog와 다름; lag=0은 business 완료 아님 |

raw에는 high-cardinality 원 identity가 필요하지만 metric label에는 넣지 않는다. 후속 #106은
`transport`, `runtime_role`, `logical_consumer`, `event_type`, `schema_version`, `delivery_state`,
`promotion_outcome`, stable `failure_code`, 제한된 workload case를 low-cardinality vocabulary로
사용한다. tenant/event/registration/Entry/Offer/Reservation UUID, group offset, token, request ID,
사용자명, payload, exception 원문은 metric label로 쓰지 않는다. raw/trace/log identity는 synthetic
실험 scope 또는 접근을 통제한 tenant-safe correlation에만 남긴다. 신규 dashboard/trace backend를
이 Issue에서 도입하지 않는다.

## Correctness oracle

판정 source는 fresh MySQL 원자료다. response/log/worker claimed count만으로 완료를 판정하지
않는다. 원 event의 canonical 의미, membership, delivery, receipt와 effect를 scoped identity로
join한다. unresolved/DEAD를 성공으로 숨기지 않는다.

- eligible original target inventory와 materialized targets를 대조하여 missing/extra/duplicate
  target을 검출한다. receipt는 같은 tenant/event/logical consumer의 의미를 원 event와 대조한다.
- PROMOTED receipt는 scoped Offer/Entry/Reservation/Allocation/notification request를 참조해야
  한다. 완료 no-op는 Offer/Entry/Reservation effect 참조가 없어야 한다. receipt outcome null,
  DONE인데 완료 receipt 없음, effect인데 receipt 없음 등 partial commit을 검출한다.
- original event의 같은 logical effect identity에 연결된 Offer는 최대 하나다. terminal Offer 뒤
  duplicate/replay도 두 번째 Offer를 만들지 않으며 notification 요청도 Offer당 하나다.
- 같은 verificationNow로 capacity-consuming active Allocation unit을 계산한다. HELD는
  `expiresAt > verificationNow`일 때만 소비하고 CONFIRMED/CHECKED_IN은 소비한다.
  raw active row 수는 diagnostic이며 due HELD를 capacity violation으로 오판하지 않는다.
- accept/reject/expiry 및 eligible FIFO의 기대 결과와 persisted Entry/Offer/backing 상태를 대조한다.
  tenant/venue/resource/slot/customer ownership이 섞인 effect를 거부한다.
- FIFO 기대값은 runner의 실제 normal/hot workload assertion으로 확인한다. raw oracle은
  resource seating/policy를 포함한 전체 후보를 별도로 재구성하지 않는다. 같은 tenant/Venue와
  시간·partySize의 독립 Slot 등록도 현재 M4의 Demand/FIFO를 공유할 수 있다.
- business atomicity/rollback, current-read, duplicate/retry/DEAD/replay 세부 경계는 관련 M4/Event
  regression으로 검증한다. 성능 run에서 과거 failure evidence를 새 실행 결과로 재사용하지 않는다.

## ADR 보존과 후속 ownership

| 기준 | 보존 | 후속 검증 / adoption 후보 |
| --- | --- | --- |
| ADR-0002 Accepted | public module 경계, MySQL local transaction, 현재 단일 Product 배포 | opt-in independent consumer/runtime 실험; 지원 deployment가 바뀌면 대체 관계를 새 ADR로 기록 |
| ADR-0006 Accepted / #88 | Slot-first, 한 commandNow, effective capacity, system failure 500 | transport/replica 변경에서도 같은 workload/oracle; lock/current-read 제거 금지 |
| ADR-0007 Accepted | immutable transactional source, at-least-once, receipt/effect atomicity, finite retry, tenant-safe replay | Kafka publication/intake는 별도 opt-in; 기본 transport 및 DB direct 장기 지위는 #111 evidence 후 결정 |
| M4 #95/#96 | FIFO/current-state, PROMOTED/no-op, admission identity, Offer lifecycle | 같은 logical consumer workload로 fan-out/runtime 독립성과 복구 검증 |

#105는 outbox source, 세 identity, durable intake handoff, single execution authority,
retry/DEAD/replay, tenant boundary, membership/cutover safety와 위 측정 어휘를 확정한다.
Kafka 기본 채택 여부, DB direct 유지 기간/목적, 최종 runtime deployment contract는 evidence
이후의 adoption 결정이다. 실험용 opt-in topology를 Accepted ADR의 즉시 대체로 기록하지 않는다.

| 후속 Issue | 이 기준선을 사용하는 책임 |
| --- | --- |
| #106 | Product request/event 관측, low-cardinality metric/trace/log와 correlation |
| #107 | committed original event의 Kafka publication/relay foundation, destination identity |
| #108 | original target/authority 검증, durable intake/offset/scoped executor, registration/discovery와 cutover |
| #109 | intake/offset/effect/retention/authority의 실제 fault matrix |
| #110 | human operator 인증·권한·audit, authorized recovery/runbook |
| #111 | 같은 workload 재측정, transport 비교와 최종 adoption/ADR 결정 |

관련 근거: [roadmap](../roadmap.md), [ADR-0002](../adr/0002-start-with-modular-monolith.md),
[ADR-0006](../adr/0006-use-targeted-pessimistic-locks-for-reservation-consistency.md),
[ADR-0007](../adr/0007-use-transactional-event-record-and-db-delivery.md),
[event delivery](../architecture/event-delivery.md), [promotion](../architecture/waitlist-promotion.md),
[request admission](../architecture/waitlist-promotion-requests.md),
[maintenance](../architecture/waitlist-maintenance.md), [experiment convention](README.md).
