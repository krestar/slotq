# Kafka intake 관측 corrective (#126)

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

Base: 최신 main `3fb033291b604673b17bf2f89a4e25a408e68e2b`. #106의 missing/zero·bounded
advisory observation, #108의 durable intake/offset/scoped authority, #111/ADR-0008의 DB direct
supported default와 Kafka experimental/comparison 지위를 유지한다.

## 결함 재현과 수정

| Finding | 변경 전 production 경로의 확인 | 최소 수정 |
| --- | --- | --- |
| Halt 뒤 stale healthy lag | 실제 `runCycle()`을 healthy empty lag 뒤 poll/intake/offset commit 실패로 전환한 세 회귀 모두 health=1이 남아 실패. 별도 degraded-state 회귀도 실패 | broker close 전에 runtime state와 lag validity 무효화. Halt/stop/rebalance/조회 실패/assignment 부재와 45초 stale은 health=0, lag NaN. Ready 표시만으로 sample을 복원하지 않음 |
| Quarantine missing을 0으로 표시 | count=3 성공 뒤 DB 조회 실패 시 0으로 변환되는 회귀 실패. Count 초기화가 query 앞에 존재 | 단일 query가 계산한 immutable count/oldest snapshot만 교체. Failure는 마지막 성공 snapshot/timestamp를 유지하며 health=0, values NaN. Stale도 같은 unknown 의미 |
| Intake cycle의 unbounded 관측 SQL | `sample()` → `JdbcKafkaIntakeStore.quarantineCounts()` → production `JdbcTemplate` 동기 경로. 명시적인 statement/socket bound 없음 | 해당 store API와 intake SQL 호출 제거. 기존 #106 daemon/별도 read-only max-one pool로 이동. Connection/connect 1초, JDBC query/MySQL MAX_EXECUTION_TIME 1초, socket 1.5초 |

Quarantine은 Kafka consumer의 `slotq.observability.database.enabled=true`와 별도 SELECT 계정이
필요하다. Default sample interval 15초/stale-after 45초다. 미설정은 absent/unknown이며 active
Kafka runtime에서 독립 alert가 탐지한다. 고정 다섯 failure code의 aggregate count/age이므로
결과 cardinality는 고정이고 table scan 비용은 statement timeout으로 제한한다. Query timeout을
count cap이나 성공한 빈 집합으로 해석하지 않는다. Scrape는 memory snapshot만 읽는다.

## 자동 regression

- `KafkaIntakeRuntimeTests`: 실제 cycle의 poll/intake/offset commit/assignment sample 실패 뒤
  halted/degraded, close 진입 전 lag invalidation, 다음 cycle 무실행. Metric은 restart authority가 아님.
- `KafkaIntakeObservabilityTests`: 실제 end=0 의미, nonempty committed provenance 누락,
  broker 실패/assignment 부재, state와 sample validity 분리, stale/revoke/partition 이동.
- `KafkaLagObservationIntegrationTests`: 실제 Kafka 4.1.1 빈 topic의 absolute end=0과 첫 committed
  offset 부재를 확인한 뒤 healthy known-zero. Degraded 후 동일 gauge는 NaN.
- `KafkaQuarantineObservationIntegrationTests`: 실제 MySQL 8.4에서 positive count→SELECT 권한
  실패→unknown→권한 복구/성공한 zero, 첫 실패, stale, 완성 전 실패, finite failure-code cardinality,
  read-only/SELECT-only 계정, uncommitted writer rollback과 nonlocking read, closed pool 뒤 memory scrape.
- 같은 MySQL suite의 finite-bound case: production quarantine table에 WRITE table lock을 잡아
  실제 observer query의 timeout 확인(<5초), max-one pool을 점유하여 connection timeout 확인(<3초),
  실제 container pause로 무응답 socket 실패 확인(<5초). Bound는 assertion이며 production SLA가 아님.
- Metadata lock 대기 중인 실제 observer thread를 확인하고 intake poll cycle이 500ms 안에
  완료함을 검증. 공유 MySQL의 business table 장애까지 격리된다고 주장하지 않음.
- 관측 SELECT만 실패한 상태에서 production `KafkaIntakeRuntime`과 실제 MySQL store/guard를
  실행. Broker commit callback은 durable prefix/target/provenance commit을 먼저 확인한다.
  Offset=1 인계 뒤 target은 attempts/fence=0 PENDING이며 receipt를 만들지 않는다. DB direct
  claim은 Kafka-owned target을 거부하고 관측 전후 durable assignment/epoch는 동일하다.
  이 focused test의 broker는 mock이며 실제 business effect/crash/cutover는 기존 영향 regression으로 확인한다.
- `infra/observability/alerts.test.yml`: 실제 Prometheus 3.5.0 promtool로 halted lag 발화/해제,
  healthy known-zero, broker health와 독립적인 quarantine failure/stale, active runtime의 관측 미설정 확인.

## 검증과 종료 상태

당시 명령/결과와 환경은 이 summary에 남긴다.
구현 commit `66f769c`의 동일 코드로 targeted 12 suite/40 case PASS(7m28s), Backend 전체 test
PASS(17m38s), clean build PASS(18m15s)를 확인했다. 두 전체 검증 모두 87 suite/707 case 중
698 PASS, 기존 opt-in 9 skip, failure/error 0이다. #126의 네 suite/19 case는 모두 실행·통과했고
skip하지 않았다. 실제 Prometheus 3.5.0 rule regression 네 group도 PASS다. Skip 목록과 명령,
source provenance는 당시 history에 있으며 skip을 새 PASS로 세지 않는다. 로컬 suite 진행 출력만을
위한 Gradle init listener를 사용했으며 test input/filter는 변경하지 않았다.
Frontend 변경은 없으며 #109 전체 fault matrix, #111 comparison/drill을 새 실행으로 세지 않는다.
당시 기존 비교와 fault drill은 재실행하지 않았다. 이번 관측 보정은 original event/business
state/target/receipt, durable intake→offset, consumer scope, claim/lease/fencing, retry budget,
membership/epoch, receipt/effect/DONE와 quiesced manual cutover/rollback을 변경하지 않는다.
Automatic fallback과 Kafka adoption은 추가하지 않는다.

M5 closure는 #126 merge 후 최신 main에서 완료조건을 다시 대조해야 한다. 이 corrective의
검증 성공을 최종 M5 종료 판정으로 사용하지 않는다.
