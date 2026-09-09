# Event 전달 경계 비교

- 상태: Measured
- Issue: [#80](https://github.com/krestar/slotq/issues/80)
- 결정: [ADR-0007](../adr/0007-use-transactional-event-record-and-db-delivery.md)
- 원자료: [clean/report.json](events/clean/report.json)
- 당시 실행 코드: `01158e9c5f7a6e65d490f54753817b03ce0b6a57`,
  branch `chore/event-delivery-boundaries`, dirty=false

## 재현

JDK 25와 실행 중인 Docker가 필요하다. backend에서 다음을 실행한다.

```powershell
.\gradlew.bat eventExperiment
.\gradlew.bat test --tests '*EventBoundaryTests' --tests '*EventRawEvidenceTests'
```

Linux/macOS는 `./gradlew eventExperiment`를 사용한다. 출력 기본값은
`backend/build/reports/experiments/events/report.json`이며 `-Poutput=<경로>`로 변경할 수 있다.
clean source에서 실행하고 생성 결과는 실행 완료 뒤 evidence 위치로 복사한다.
`EventRawEvidenceTests`는 repository에 보존된 clean report를 읽고 summary 전체와 fault별
DB 결과를 재계산·검증한다. 새 run을 채택할 때만 해당 원자료를 명시적으로 교체한다.

테스트 전용 schema는 독립 Testcontainers MySQL에 생성한다. production Flyway migration,
event endpoint, worker service, Product event는 없다. runner/fixture/probe는 모두
`backend/src/test/java/com/slotq/experiments/events`에 있어 bootJar에 포함되지 않는다.

## 고정 환경과 workload

| 항목 | 관측/설정 |
| --- | --- |
| 실행 시각 | 2026-09-09T13:45:02.096388200Z |
| Java / Spring Boot / Gradle | 25.0.4.1+1-LTS / 4.1.1 / 9.7.1 |
| MySQL / isolation | mysql:8.4, 8.4.11 / REPEATABLE-READ |
| Pool | Hikari, 최대 10, connection timeout 30,000ms |
| Host | Windows 11, AMD Ryzen 7 6800H, 16 logical processors |
| Host memory / storage | 16,329,510,912 bytes / WD PC SN810 SDCPNRY-512G-1006 SSD |
| Container | Docker Desktop 4.87.0, Engine 29.7.2, 별도 container CPU/memory limit 없음 |
| Network | local loopback와 Docker bridge, traffic shaping 없음 |
| Schema | test-only fixture v1; Product Flyway V8과 분리 |
| 비교 | 3 후보 × 5 fault × 3 반복 = 45 cases |
| Seed | 후보마다 80001부터 동일 15개; 각 후보 시작 시 fixture 데이터 reset |
| Business fixture | tenant-owned owner state, event별 effect PK, current-state projection |
| Fault | NONE, BEFORE_COMMIT, AFTER_COMMIT, HANDLER_FAILURE, SLOW_HANDLER(100ms) |
| 추가 probe | 같은 logical fixture의 중복/위조/역순, outer rollback, JSON, retry/replay, crash budget 총 44개 |
| Duplicate | 순차 3회, 4-worker barrier concurrent first delivery, 새 event 뒤 20ms 지연 redelivery |
| Protocol probe | 3 durable claims/cycle, 300ms MySQL lease, backoff 0; production 값 아님 |

후보 순서는 고정되어 있으며 warm-up을 분리하지 않았다. latency의 일반적 우열이나
production SLO를 주장하지 않는다. fault 없는 별도 load benchmark도 아니다.
정합성 비교의 DB/transaction/handler는 동일하고, handler를 commit 안/밖/복구 worker 중
어디서 실행하는지만 다르다. 이 구조적 차이를 숨기기 위해 fault 정의를 바꾸지 않는다.

## 관측과 판정

| 후보 | Cases | Business commit | 복구 후 effect | 유실 | commit 직후 crash 유실 | 100ms handler 조건 producer 평균 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| SYNCHRONOUS | 15 | 9 | 9 | 0 | 0 | 116.7366ms |
| DIRECT | 15 | 12 | 6 | 6 | 3 | 122.0025ms |
| DURABLE | 15 | 12 | 12 | 0 | 0 | 10.3751ms |

유실은 `business=1 AND effects=0 AND durable=0`이다. durable pending/DEAD는 유실이나
성공으로 섞지 않는다. direct의 유실은 post-commit crash 3건과 handler failure 3건이다.
동기의 handler failure 3건은 business rollback이므로 committed event 유실로 세지 않는다.
durable의 producer latency에는 worker handler 실행이 포함되지 않으므로 end-to-end latency
우위로 해석하지 않는다. direct는 commit 밖이지만 같은 caller thread에서 handler를 기다린다.

raw `rows`에는 event envelope, seed, fault, repetition, child exit code, producer elapsed와
recovery 전후 DB row count가 있다. `summary`는 이 row에서만 계산한다. 추가 `probes`에는
event별 effect의 immutable 의미, projection, delivery state/attempt/token/lease와 replay audit
DB row를 보존한다. fixture event 외 실제 개인정보와 credential은 기록하지 않는다.

## Fault와 원자료 위치

| 검증 | 원자료 / 결과 |
| --- | --- |
| pre-commit rollback | 각 후보 BEFORE_COMMIT 3건, business/event/effect 0 |
| actual post-commit process exit | 각 후보 AFTER_COMMIT 3건, 별도 JVM halt exit 80 |
| restart discovery | DURABLE recovery는 새 JVM의 `fixture_event` scan; parent event 객체 전달 없음 |
| caller transaction 참여 | OUTER_ROLLBACK에서 inner producer 종료 후 outer rollback, business/event 0 |
| standalone append | APPEND_WITHOUT_TRANSACTION 거부 |
| malformed JSON | INVALID_JSON DB rejection 및 business/event rollback |
| consumer failure | 동일 HANDLER_FAILURE; 동기는 business rollback, direct는 유실, durable는 source 보존 뒤 복구 |
| duplicate / concurrent | 후보별 SEQUENTIAL/CONCURRENT/DELAYED_DUPLICATE, effect 각각 1 |
| ordering | 후보별 REVERSED, 2→1 적용 뒤 projection=2 |
| forged tenant / meaning | 후보별 FORGED_TENANT/IDENTITY_CORRUPTION 거부, 기존 effect 의미 보존 |
| transient / exhaustion | TRANSIENT 1회 실패 뒤 DONE, EXHAUSTION 3회 후 DEAD, auto claim 차단 |
| durable poison | UNKNOWN_TYPE/UNKNOWN_VERSION/PAYLOAD_INVARIANT, valid JSON + DEAD, effect 0 |
| claim crash | AFTER_CLAIM_CRASH_EXIT_80, reclaim의 새 token 이후 stale owner 거부, DONE |
| effect commit / ack gap | AFTER_EFFECT_CRASH_EXIT_80은 effect 1 + PROCESSING; reclaim 후 DONE/effect 1 |
| crash exhaustion | AFTER_CLAIM_BUDGET_1..3은 실제 JVM 종료; AFTER_CLAIM_EXHAUSTED는 DEAD/attempts 3 |
| replay | untrusted/cross-tenant 거부, REPLAY_AUDIT 1 row, 같은 ID로 DONE; cycle 1/lifetime 4 |
| external response 전후 timeout | 첫 실제 external-side-effect/provider Issue로 ownership 이동; 아래 경계 참조 |

sync/direct에는 durable retry/exhaustion/replay 상태가 없다. sync는 handler 실패 때 business가
rollback되며, direct는 handler 실패·crash 뒤 잃은 event를 찾을 source가 없다. 이 결함을
가상의 durable queue로 보충하지 않았다. protocol probe는 후보 공통 workload 뒤 durable
record를 사용할 때 추가로 필요한 비용/정합성을 검증한다. generic runtime 전체의 성능 비교는 아니다.

claim/reclaim의 경과는 MySQL lease predicate를 조회해 기다린다. process 종료를 Java exception으로
대체하지 않는다. transient handler failure와 pre-commit rollback은 명시적 exception 주입이다.
probe의 owner lock/unique effect는 consumer 경계를 검증하는 synthetic model이며 M4 실제
Waitlist ordering/offer invariant의 완료 증거가 아니다.

## 선택과 범위

ADR-0007은 transactional event record와 same-deployment DB delivery를 선택한다. 동기 처리는
올바르지만 downstream 실패를 Booking commit에 결합하고, direct는 유실을 복구할 수 없다.
DB 방식이 부족한 fan-out/throughput/독립 deployment 근거는 관측하지 않아 broker를 추가하지 않는다.

M3는 DB effect의 retry/recovery와 tenant-safe internal replay primitive를 소유한다. 사람
operator identity/UI/운영 authorization·audit/runbook은 M5다. 실제 Booking producer append와
Waitlist consumer 연결은 M4 vertical slice다. external response-before/after timeout은 첫 실제
external-side-effect/provider Issue의 필수 gate로 이동한다. 그 Issue가 provider idempotency와
ambiguous outcome를 검증하기 전 외부 전송의 중복 방지 완료를 주장할 수 없다.

## #80 완료조건 직접 대조

| #80 조건 | 대응 결과 |
| --- | --- |
| 최신 main/MySQL 8.4 고정 workload·명령 | origin/main 4cd88b9에서 분기, 위 재현 명령과 clean manifest |
| 세 후보 동일 correctness/failure 비교 | 동일 seed 45 rows, 공통 handler, fault별 raw count |
| direct crash window/recovery | 실제 halt 3건, durable source 없음과 유실 3건 |
| durable atomic commit/rollback | BEFORE_COMMIT + OUTER_ROLLBACK + standalone guard |
| 유실/중복/exhaustion/restart 원자료 | rows/probes DB snapshot과 summary 재계산 test |
| identity/envelope/version/tenant | ADR identity·routing·tenant 절, 위조/meaning/poison probe |
| 역순 및 ordering | REVERSED projection 2, transport ordering 없음·M4 guard 의무 |
| retry taxonomy/bound/replay | transient/poison/exhaustion/crash/replay probe와 ADR |
| claim/lease/receipt protocol | ADR 상태 전이·attempt·DB time·fencing·transaction 입력 |
| operator replay ownership | M3 internal primitive / M5 human surface로 문서 동기화 |
| external timeout ownership | 첫 실제 provider Issue 필수 gate로 명시적 이동 |
| broker 보류/재검토 | ADR의 측정 기반 trigger |
| 새 Accepted ADR | ADR-0007 |
| Register/experiment/roadmap 일치 | 관련 문서의 선택·ownership 동기화 |
| clean/raw/integrity 보존 | clean/report.json + EventRawEvidenceTests |
| test 전용 경계 | 모든 실행 코드 src/test, production schema/event/worker 변경 없음 |
| Backend tests/clean build | 관련 event tests, 전체 test 193개 및 clean build 성공; 실패·오류·skip 0 |

탐색의 초기 원자료와 JSON round-trip 실패는 [탐색 기록](events/exploratory/README.md)에 보존한다.
역사적인 M2 evidence는 변경하지 않았다.

최종 문서 검토에서 consumer activation/cutover가 과거 event 대상과 처리 책임을 암묵적으로
바꾸지 않는 의미, M3 automatic cleanup 미도입 경계를 ADR에 보강했다. 저장 schema와 보존
기간은 선결정하지 않는다. 실제 external provider의 도입 Milestone도 고정하지 않으며,
M4가 request contract만 구현하면 provider timeout 검증은 실제 연결 Issue까지 남는다.

검증 기록: 동일 Backend 코드 revision `01158e9`에서 event regression/integrity test와
전체 `test`(193개)가 성공했다. 이어 `clean build`도 2026-09-09에 4m 31s로 성공했고
193개 tests의 failures/errors/skipped는 모두 0이다. 재개 시 보존된 Gradle 완료 로그와
XML, Backend diff 없음, clean raw SHA-256
`a61bf2d5404374015e45059473dfa7ed8d70f3450ab09f4ac3aa1a98133fc8bc`를 확인했다.
후속 변경은 문서뿐이므로 실험과 Backend 검증을 반복하지 않았다.
