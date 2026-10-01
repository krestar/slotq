# ADR-0008: DB direct 기본 전달과 Kafka experimental topology 유지

- 상태: `Accepted`
- 결정일: 2026-10-01
- 관련 Issue: [#111](https://github.com/krestar/slotq/issues/111)
- 근거: [비교 protocol/15회 결과](../experiments/m5-transport/README.md),
  [canonical 비교](../experiments/m5-transport/2026-10-01-comparison/summary.json),
  [fresh 통합 drill](../experiments/m5-transport/2026-10-01-drill/summary.json),
  [완료조건 대조](../experiments/m5-transport/closure.md)
- 대체: ADR-0002의 단일 runtime 배포 조항과 ADR-0007의 초기 transport/runtime 선택.
  두 문서의 당시 근거와 아래 보존 계약은 유지한다.

## 별개의 결정

| 질문 | 결정 |
| --- | --- |
| A. 기본 event distribution transport | `DB_DIRECT` 유지. 현재 supported Product topology의 기본값이며 Kafka default 채택은 보류한다. |
| B. Kafka implementation의 repository/runtime 지위 | opt-in **reproducible experimental/comparison topology**로 유지한다. 상시 운영 supported alternative나 영구 production fallback으로 채택하지 않는다. |
| Kafka candidate adoption 범위 | local/container의 실제 fan-out, 독립 JVM·group, replica assignment, durable intake·복구·수동 rollback 검증에 한정한다. 배포 간 독립 data ownership, HA 또는 production 성능을 승인하지 않는다. |
| DB direct 장기 지위 | **permanent supported default이자 quiesced 수동 rollback target**. Kafka로 이전할 기한이나 DB retirement horizon을 정하지 않는다. |

Kafka가 default가 아니라는 이유로 구현을 삭제하지 않는다. 이 구현은 원 event/target/receipt
identity, intake-before-offset, ACK unknown/redelivery, epoch와 operator boundary를 실제 broker로
재현하는 유지 대상 실험 경로다. 기존 구현이라는 이유로 상시 운영 support를 약속하지도 않는다.
Broker/client, schema/authority, secure profile, codec, fault harness와 runbook 회귀 비용을 부담한다.
이 검증 가치를 유지할 소유권이나 재현성이 없어지면 별도 corrective/decommission 범위를 먼저
정하고 재검토한다. 이번 결정에서 schema/data/API 제거 또는 대규모 migration은 하지 않는다.

## 확인한 evidence와 한계

동일 seed/public trace, Waitlist + observer, MySQL RR/pool 10, receipt 의미, retry budget 5,
consumer scope와 telemetry를 사용했다. DB 1/3 executor, Kafka 1/3 executor, 추가 broker budget의
5 profile을 각각 3회 실행했다. 원 event 615/target 1230에서 loss, duplicate logical effect,
partial receipt/DONE, capacity violation, missing membership/authority와 unexplained DEAD/quarantine 0이다.

| 축 | DB direct | Kafka | 판단 |
| --- | --- | --- | --- |
| Business correctness | 같은 immutable source, scope/receipt/DONE/fence oracle PASS | 같은 oracle PASS; offset은 intake handoff | 정합성 mechanism을 performance와 교환하지 않음 |
| Logical consumers | shared discovery가 두 original membership을 materialize, scoped executor/projection | 두 독립 group이 같은 원 event를 intake | DB도 consumer scope·별도 observer 실행 가능 |
| Process isolation | 기본 Product에 Waitlist executor/maintenance가 남음. Observer는 분리 가능 | Product produce-only, relay/Waitlist/observer 분리와 실제 장애 독립성 확인 | Kafka의 실제 process 경계 장점 인정; DB producer-only 배치를 주장하지 않음 |
| 1→3 / backlog | scoped worker 확장과 backlog drain, 실제 row-lock 경쟁 관측 | 3 member/partition assignment와 drain 수렴, 추가 broker budget도 측정 | finite closed-loop trace에서 distribution capacity 이득은 입증되지 않음; distributed scale-out의 부정 증거도 아님 |
| Recovery | 원 ledger에서 finite attempt/replay; 새 drill의 수동 rollback 뒤 scoped DB 복구 | restart, broker outage, ACK unknown 재전달, durable intake 뒤 실제 DB outage/recovery와 operator audit 확인 | 양쪽 runbook/regression 유지 필요. 자동 fallback 없음 |
| Resource | broker 없음, MySQL/JVM 비용 기록 | broker peak 표본 약 571–600 MiB, CPU/network/log storage 추가 | 동일 cap 합계와 extra allocation을 분리; aggregate host cgroup 제한은 아님 |
| Operations | shared DB/host, discovery/claim/fence, human authorization 관리 | 위 비용에 topic/ACL/TLS/group/prefix/offset/retention, relay lifecycle 추가 | 현재 지원 topology에서 상시 운영 추가 비용을 정당화할 충분한 요구/evidence 없음 |

단순 latency 순위를 결정 규칙으로 사용하지 않는다. 현재 측정은 single host/container의
instrumented finite **closed-loop production-like workload**다. 실제 production traffic, open-loop
saturated capacity, HA/SLO 또는 여러 host의 throughput으로 표현하지 않는다. M4 single-consumer
baseline은 다른 workload의 참고값이다. DB/host clock, transaction timestamp/관측 상한과 sampling
한계는 비교 manifest를 따른다.

비교 cohort는 마지막 empty-partition lag 관측 수정 전의 source다. 전체 production source와
public trace/비교 runner/calculator hash 대조에서 차이는 이 read-only lag sampler 한 파일뿐이다.
수정은 기존 broker end/committed 조회의 빈 partition 판정이며 추가 broker/DB I/O, append/intake/
claim/receipt/retry 의미를 바꾸지 않는다. 기존 측정 source를 재표기하지 않고, 최신 sampler의
positive/negative regression과 fresh broker outage/alert 복구를 별도로 통과시켰다.

Fresh drill은 original 9/target 18 DONE, human operation/audit 각 2로 수렴했다. Booking HTTP는
observer 중단, Waitlist DEAD, broker 중단과 rollback 뒤 DB 대표 장애에서도 201이었다. Broker
재전달은 두 consumer의 기존 logical target을 재사용했다. 실제 MySQL pause 동안 protected scrape
200/unhealthy와 production `EventDeliveryWorker.runCycle()`의 JDBC failure를 확인했다. 장애
탐지/해제와 convergence는 raw의 실제 시각/nanos로 계산하며 production 복구 SLA로 사용하지 않는다.

Drill의 Product maintenance/local execution timer는 test adapter로 제어했다. 따라서 DB mode의
전체 Waitlist process를 Product와 독립적으로 끄는 producer-only 배치의 증거가 아니다. 비교의
co-located executor도 독립 process 6개의 성능값이 아니다. 이 제한을 Kafka 장점이나 DB 단점의
추정으로 대체하지 않는다.

## Supported runtime roles

같은 Product artifact와 schema를 사용하며 module을 별도 service/database로 분해하지 않는다.
기존 enabled/readiness 조건과 scheduler disabled 기본 설정을 유지한다. 아래 role은 명시적인
activation과 durable transport/epoch admission을 통과한 실행 경계다.

| Runtime | 지위와 경계 |
| --- | --- |
| Product DB direct | 기본 supported runtime. API/producer, Waitlist exact routes/readiness, local scoped executor/maintenance. Booking business와 event append는 같은 MySQL transaction. |
| DB direct observer consumer | supported opt-in logical consumer. 자기 registration/transport/epoch의 projection/receipt만 실행; Product 역할을 대신하지 않음. |
| DB direct Waitlist consumer replica | 기존 scoped executor를 추가 실행할 수 있음. Product의 local Waitlist worker를 제거하거나 producer-only activation을 보장하지 않음. |
| Kafka Product / relay / Waitlist consumer / observer consumer | experimental opt-in. 각 역할의 기존 readiness/ownership/scope/authority guard와 secure profile 유지. 상시 운영 alternative support 승인 아님. |
| Cutover | supported one-shot quiesced maintenance command. 전체 Product writer/maintenance/relay/intake/executor 정지, fence/scan/READY 뒤 재개. |
| Isolated role management HTTP | web none 또는 명시적 management-only servlet. TLS + 별도 machine credential의 Prometheus GET만 허용. Product/operator HTTP는 노출하지 않음. |
| Human recovery | Product private HTTPS operations boundary의 individual operator + tenant/consumer/action grant, exact state/fence/epoch, 동일 operation id와 atomic append-only audit. |

Kafka↔DB는 #108의 수동 quiesced cutover/rollback만 지원한다. 이번 fresh drill은 Kafka→DB와
epoch 2→3, original target/attempt/receipt 보존을 검증했다. 양방향 전환과 stale owner는 merged
#108/#109 및 cumulative regression 근거를 사용한다. 필요 없는 재전환을 새로 수행했다고
표현하지 않는다. Runtime restart/cutover/recovery에 raw Kafka offset, SQL row 변경 또는
test-only SystemPrincipal을 사용하지 않는다.

## Retention과 recovery horizon

Broker retention 24h는 **실험 profile 설정**이다. RF=1 비교/drill과 같은 host의 RF=3 fault
profile은 cloud HA 근거가 아니다. Kafka 자동 intake recovery는 topic identity, log-start,
committed offset, DB durable prefix가 모두 기존 guard를 통과하는 범위에 한정된다. Offset expiry,
topic 재생성/retention gap은 fail closed하며 silent latest reset이나 자동 fallback은 없다.

현재 relay `verifyTopic`은 **모든 PUBLISHED row의 최소 ack offset**을 필요 위치로 취급한다.
Intake와 business DONE을 마친 row도 제외하지 않는다. 정상 retention 전진이 relay를 fail closed할
수 있어 24h 설정을 uninterrupted runtime/recovery 보장으로 사용할 수 없다. 이 보수적 guard의
운영 부담은 Kafka 상시 운영 adoption의 남은 제약이다. Guard를 약화하거나 safe handoff 완료를
가정하지 않았다. #110의 approved publication recovery는 정확한 blast radius와 audit로 수행하지만
그 작업만으로 expired consumer prefix/group을 복구할 수 있다고 주장하지 않는다.

Gap에서 committed business의 복구 근거는 MySQL의 원 event/registration/target/receipt다.
보존 inventory와 원인을 확인하고 승인된 quiesced DB rollback을 판단한다. Kafka 재전환은
topic/group/prefix가 다시 admission 가능한 경우에만 수행한다. 이미 유실된 broker coordinate에
대한 임의 offset/prefix reset이나 무조건 재전환은 지원하지 않는다. 장기 Kafka adoption을
재검토하려면 retention-aware responsibility/recovery 계약과 실제 회귀 evidence가 먼저 필요하다.

DB original event, registration/assignment, delivery, receipt, publication/intake, recovery operation/
audit에 자동 TTL/cleanup은 없다. 실제 보존된 durable source와 호환 schema/authority가 recovery
horizon의 조건이며 무한 보존·무한 용량 또는 시간 SLA를 보장하지 않는다. Storage 성장과
readonly inventory cap/staleness를 운영 비용으로 관리한다. 삭제/backup/restore/HA/failover 계약을
이번 Issue에서 새로 만들지 않는다. M8 backup/restore ownership을 유지한다.

## 남은 failure domain과 regression 비용

Business, append fence, discovery, publication/intake, claim/receipt/audit는 같은 MySQL에 의존한다.
독립 consumer JVM이나 Kafka가 MySQL outage·row-lock/pool 경합을 격리하지 않는다. Product의
local Waitlist worker/maintenance는 API와 process를 공유한다. 모든 local broker/collector/JVM은
host/VM/disk/network를 공유한다. DB/host 상실의 복구는 이번 evidence의 범위가 아니다.

DB direct를 영구 지원하므로 discovery/scope, receipt/DONE, FIFO/current read/effective capacity,
lease/retry/fence와 original membership regression, DB runbook 및 authorized replay를 계속 유지한다.
Kafka experimental path도 실제 broker intake/relay/cutover/security 및 canonical/fault harness를
유지한다. 관련 변경에는 focused regression, Backend 전체 test/clean build, 필요한 Frontend 검증을
적용한다. Runtime/retention/recovery boundary가 바뀌면 새 대표 drill을 실행하고, 같은 manifest/
trace/budget으로 performance를 비교할 때 profile당 최소 3회와 raw 재계산을 유지한다.
Low-impact 변경마다 #109 전체 matrix를 반복하지 않는다.

## ADR 보존·대체와 재검토

- ADR-0002: public port/event contract, entity/table ownership, 비순환 module dependency, 하나의
  Product codebase와 MySQL/local business transaction을 보존한다. 초기의 단일 runtime 배포
  조항을 위 명시적인 role 지위로 대체한다. Microservices/data ownership 분리는 승인하지 않는다.
- ADR-0007: transactional immutable append, authoritative source, identity/membership, receipt/DONE,
  finite retry/fence/unknown/replay 및 안전한 metadata transaction을 보존한다. 초기 M3의
  같은 process DB polling만 허용하고 Kafka를 도입하지 않는 단계 선택을 현재 role/transport
  결정으로 대체한다. 이전 비교와 역사적 결정 본문은 다시 쓰지 않는다.
- ADR-0006/M2~M4: Slot-first locking, 단일 commandNow, locking current read, effective capacity,
  Waitlist FIFO/normal no-op와 권한을 보존한다.

Production-like 요구, 실제 부하/실행 boundary와 유지 소유권이 현재 topology의 비용을 넘고,
retention/recovery와 새 3회 이상 비교/대표 drill이 이를 뒷받침하면 default와 Kafka 지위를 각각
재검토한다. M6의 새 AI consumer/delegation, Redis/cache, Kubernetes/multi-region/MySQL HA 또는
M8 전체 release/backup gate를 이 결정으로 승인하지 않는다.
