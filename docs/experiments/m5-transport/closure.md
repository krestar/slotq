# M5 / #111 완료조건 대조

2026-10-01에 remote main `7a38901`과 PR #122 branch에서 재개해 완료조건을 직접 대조했고,
PR #122는 이후 squash merge되어 현재 main에 반영됐다. Issue state, merge, historical evidence와
fresh 실행은 서로 다른 근거다. 아래 [#111 local 검증](verification.json)은 2026-10-01의 역사적
gate PASS다. 이후 Kafka intake 관측 결함 세 건이 #126의 M5 종료 corrective로 확인됐다.
[#126 수정·검증](../kafka-intake-observability/2026-10-03/README.md)을 별도로 대조하며,
corrective merge 뒤 최신 main의 완료조건 재확인 전까지 최종 M5 종료 판정은 보류한다.

## #105~#110의 실제 완료조건

| Issue / merge | 완료조건별 구현·evidence 대조 | 판정 |
| --- | --- | --- |
| #105 / PR #112 `9a934a5` | 실제 public M4 path와 3회 DB raw/manifest; PROMOTED/normal no-op와 FIFO/current-state/capacity/receipt oracle; publication/target/receipt identity; durable intake→offset→scoped executor/crash 책임; original registration/discovery/retention/cutover; clock/분모/cardinality; fixed protocol과 adoption 후보; ADR-0002/0006/0007 보존·변경 표. [protocol](../waitlist-baseline.md)과 [historical baseline](../waitlist-baseline/2026-09-26-db-direct-1worker/summary.md) 확인. #111은 같은 trace/oracle에 두 consumer를 적용하고 M4를 다른 workload의 참고값으로 유지. | 완료조건 대응 있음 |
| #106 / PR #115 `16ba6b6` | request correlation→event/effect origin/attempt link; DB query/dashboard/alert; fixed Kafka query/panel/cardinality 계약; separate machine credential/read-only pool와 negative boundary; label/redaction; collector outage의 Product 독립성; 최소 client correlation 선택. [실행](../product-observability/2026-09-27-db-direct/README.md), [blocker correction](../product-observability/2026-09-27-blocker-regression/README.md), 현재 metadata-only scrape regression 및 fresh Grafana/Tempo/alert 확인. | 완료조건 대응 있음 |
| #107 / PR #116 `de95fc2` | committed append만 publication/rollback 제외; separate identity/cursors/attempts; ACK/marking crash·duplicate/multi-relay fencing; durable assignment/readiness와 dual authority 거부; tenant/schema/canonical/ACL/retention gap; consumer 준비 전 Kafka business 금지. [relay](../kafka-relay/2026-09-28/README.md)와 [correction](../kafka-relay/2026-09-28-blocker-regression/README.md), fresh ACK-before-ledger 죽음→expired-claim alert→두 consumer 재전달 수렴 대조. | 완료조건 대응 있음; conservative retention guard는 보정된 것으로 세지 않음 |
| #108 / PR #119 `fd3510a` | full M4 vertical slice; Waitlist/observer group/process; durable intake commit/offset prefix; offset 뒤 attempts=0 PENDING 최초 DB 실행; 양쪽 scoped execution/shared discovery; effect/receipt/DONE와 M2~M4; quarantine/rebalance/retry/DEAD; 양방향 quiesced original inventory/epoch; 실제 Kafka panel. [원자료/실행](../kafka-consumer/2026-09-29/README.md), crash/cutover/vertical-slice regression과 fresh two-consumer drill을 대조. 이번 cold-target RR race를 재현·수정하고 회귀에 포함. | Closed/merged. 완료조건 checklist는 evidence 대조 뒤 실제 상태에 맞춰 갱신 |
| #109 / PR #120 `57cfd3c` | 실제 process/broker/DB matrix; intake 전/후/offset 뒤 crash 각각; broker redelivery 없는 PENDING 실행; 모든 original target의 책임 inventory; integrity oracle; consumer/API 독립성; stale owner/rebalance/retention/quarantine; 허용 recovery 뒤 bounded drain. [matrix와 Sept30 추가 원자료](../kafka-fault/2026-09-29/README.md) 및 integrity regression 대조. 이번 실행은 대표 통합 drill이며 matrix 전체 재실행이 아님. | 완료조건 대응 있음. 당시 clean build는 **미실행**; 이번 cumulative gate로 별도 검증 |
| #110 / PR #121 `7a38901` | personal human identity/발급·만료·철회/mapping; tenant/consumer/action과 Product-role 분리; replay/audit atomicity; duplicate/stale/concurrent/unknown; business/publication 권한 분리; publication operation/audit와 broker I/O 분리; response loss/duplicate/audit rollback; DB/Kafka exact scope; private HTTPS API/CLI/read/audit/runbook. [53-case evidence](../operator-recovery/2026-09-30/README.md), 현재 regression 및 fresh 양 transport의 같은 operation retry→audit 각 1→receipt/DONE 대조. | 완료조건 대응 있음 |

위 historical PASS를 이번 source의 test 또는 fresh fault PASS로 재표기하지 않는다. Frontend의
최소 correlation은 #106에서 선택한 구현을 유지하며 전체 typecheck/test/build를 이번에 실행한다.

## #111 완료조건

| Issue 완료조건 | 이번 구현/실제 증거 | 판정 |
| --- | --- | --- |
| 같은 business workload/consumer 수/oracle 비교 | 같은 #105 public trace/seed + steady extension, Waitlist/observer, 5 profile 각 3회 | PASS |
| warm-up/반복/resource/time와 raw 재계산 | 별도 fresh schema/topic warm-up, 역순 repeat, same cap vs extra broker, clock/closed-loop 한계, 15 gzip raw→summary/CSV/round-trip | PASS |
| duplicate/partial/capacity/unexplained loss 0 | 비교 615 original/1230 target, drill 9 original/18 target, 각 독립 durable reconciliation | PASS |
| Kafka lag와 DB backlog/effect 별도 | durable-intake/group end/committed와 DB target/receipt/DONE 별도 summary/metrics; empty end 0만 known zero | PASS |
| observer/Waitlist/broker/DB 장애 + #106/#110 convergence | fresh observer/Waitlist isolation, broker restart·ACK unknown, 실제 DB pause/read unavailable+protected scrape, real production-cycle JDBC failure, authenticated operator operation/audit | PASS |
| Kafka↔DB original target/attempt/receipt 보존 | fresh 전체 quiescence→Kafka→DB epoch 2→3 전후 row 대조 + merged 양방향/stale epoch 및 현재 cutover regression. 필요 없는 재전환을 이번 실행으로 세지 않음 | PASS |
| isolation/resource/operations/shared domains | broker cost 포함 비교, 실제 independent groups/JVM, scoped DB observer/replicas, Product-local Waitlist/timer-adapter 한계, shared MySQL/host와 conservative retention 비용 | PASS |
| default와 DB 지원수명/rollback horizon | ADR-0008: DB direct permanent supported default/manual rollback, Kafka adoption 보류. 별도로 repository/runtime을 experimental/comparison으로 확정 | PASS |
| automatic fallback 없음 | assignment/epoch와 quiesced manual command, offset/SQL/SystemPrincipal recovery 대체 없음 | PASS |
| ADR와 0002/0007 보존·대체 | 새 Accepted ADR-0008, 기존 본문 보존 + Superseded pointer, module/transaction/receipt/fence 계약 재확인 | PASS |
| 선행 실제 완료조건과 M5 종료 대조 | 위 6 Issue의 구현/원자료/검증 대응, checkbox/미실행/historical와 fresh 구분 | PASS |
| README/roadmap/Register 정합성 | actual supported default/Kafka 지위/role/horizon 및 PR와 main 상태 구분 | PASS |
| 관련 regression/전체 Backend/clean build/누적 Frontend | 관련 12 suite/94 case + 15 dataset 재계산 PASS, Backend 전체 test PASS(17m52s), clean build + drill 재계산 PASS(17m58s). Clean JUnit 84 suite/691 case: 682 PASS/9 opt-in skip, failure/error 0. Frontend 12 file/210 test/typecheck/build PASS | PASS; opt-in skip은 새 PASS로 세지 않음 |

## Blocker와 채택 제약의 구분

2026-10-01 #111에서 선택한 supported DB topology의 correctness/security/transaction/concurrency
blocker를 관측하지 않았고 당시 필수 local 검증이 통과했다. Opt-in historical fault/security/diagnostic
9 case는 fixture 없이 skip됐으며 exact 목록을 verification.json에 남겼다. #109 전체 matrix를
이번 실행으로 재표기하지 않는다.

Kafka의 상시 운영 adoption에는 다음 제약이 남는다. 완료된 PUBLISHED row도 최소 ack offset
guard에 포함되어 retention 전진이 relay를 fail closed할 수 있고, expired group/prefix의 무조건
재전환 recovery surface는 없다. 이를 해결했다고 주장하거나 guard를 약화하지 않았다. Actual
independent process 경계는 확인했으나 finite single-host trace는 distributed capacity 이득이나
상시 운영 resource/ownership 비용의 정당화 근거가 아니다. **Kafka production adoption을 보류하고
experimental 지위로 유지하는 것이 이번 확정 결정**이다. 이 경로를 long-lived supported alternative로
확대하려면 retention-aware 책임/recovery 계약과 evidence를 소유하는 후속 작업이 필요하다.

DB producer-only와 완전한 Waitlist process 분리도 검증되지 않았다. 현재 default의 Product-local
Waitlist 경계를 ADR/runbook에 그대로 기록한다. 이를 제공하려고 이번 범위에서 activation/security
계약을 새로 만들지 않았다. 제거/decommission이나 후속 Milestone 기능을 선도입하지 않는다.

## #126 Kafka intake observability corrective

최신 main `3fb0332`에서 #106/#108/#111과 production 코드를 재대조해 세 finding을 확인했다.
Healthy lag 뒤 halt/degraded에서도 health=1이 남았고, quarantine DB 실패는 기존 count를 0으로
덮어썼다. Quarantine 조회는 production JDBC를 통해 intake cycle에서 명시적인 query/socket
bound 없이 동기 실행됐다. #126은 lag validity/unknown, 성공한 quarantine snapshot의 교체,
별도 read-only observer의 finite timeout과 독립 sample health/age로 이 관측 계약을 보정한다.

실제 MySQL/Kafka timeout·empty partition과 runtime halt, durable intake/offset/target authority
회귀 및 최신 Backend 검증은 [별도 기록](../kafka-intake-observability/2026-10-03/README.md)을
따른다. 기존 비교 dataset과 #109 matrix/#111 drill을 이번 source의 새 실행으로 세지 않는다.
DB direct supported default, Kafka experimental/comparison 지위와 ADR-0008은 유지한다.

## M5 종료 상태

#111의 당시 integrated gate, architecture 결정과 cumulative 검증은 PASS이며 PR #122 squash
merge로 main에 반영됐다. #126의 관측 corrective는 별도 종료 blocker다. 수정·검증 결과와
merge 후 최신 main 완료조건을 대조한 뒤 별도 종료 절차에서 최종 M5 상태를 확정한다.
Kafka adoption/retention 제약, M6 ownership과 M8 release/backup gate는 변경하지 않는다.
