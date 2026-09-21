# Waitlist promotion request admission / discovery

Issue [#96 §5](https://github.com/krestar/slotq/issues/96)의 세 번째 checkpoint다.
Commit 2 `4156053`의 [promotion effect](waitlist-promotion.md)를 바꾸지 않고, release 없는 기회를
새 `waitlist.promotion-requested` v1 event로 연결한다. maintenance expiry/reconcile, durable bootstrap,
scheduler 활성화, 별도 JVM crash/DB outage 실험은 후속 checkpoint다.

## Public 경계

- `WaitlistPromotionDiscovery.discover(SystemPrincipal, afterSlotId)`는 bounded keyset **한 page**다.
  scheduling/cursor persistence/HTTP entry point는 없다. 반환한 nextCursor로 다음 page를 호출하고,
  null이면 sweep 끝이다. 다음 sweep 또는 component restart는 null부터 다시 시작할 수 있다.
- Booking 소유 `PromotionAvailabilityQuery`는 stored Slot ownership/context와 effective capacity를
  **nonlocking advisory read**한다. 기존 capacity=1 predicate를 읽기 hint로만 사용하며 #95의
  authoritative current-read, Slot-first serialization 또는 저장 구조를 대체하지 않는다.
- Waitlist의 `hasWaiting`은 정확한 tenant/Venue/시간대와 현재 seatingCapacity에 맞는 WAITING
  존재 여부만 읽는다. queue aggregate/Entry lock/HOLD/Offer 생성은 없다.
- `WaitlistPromotionRequestUseCase.request(SystemPrincipal, venueId, slotId)`는 Slot 하나를 새로
  관측해 admission을 시도한다. integration 구현이 narrow Booking/Waitlist public port와 M3 append를
  조합한다. caller supplied tenant/payload/event ID는 받지 않는다.
- foundation의 새 `EventRecordQuery.find(tenantId,eventId)`는 exact scoped immutable record의
  nonlocking 조회만 제공한다. 원 event와 receipt 의미 비교에 필요하며 append/cutover용 global
  locking lookup을 재사용하지 않는다. foundation은 business concrete type을 참조하지 않는다.

## Durable identity / 완료 oracle

V13 `waitlist_promotion_requests`는 `(tenant_id,slot_inventory_id)` PK와 nullable `last_event_id`만
저장한다. 처음 만들었지만 기회가 사라진 anchor는 null일 수 있다. 별도 request state, completion
ledger, attempt/token, retry budget, cleanup 또는 event payload 복사본을 두지 않는다.

새 admission은 insert-first upsert + exact PK current lock으로 serialize한다. 마지막 ID가 있으면
기존 `(tenant,waitlist.promotion,eventId)` receipt를 shared current read한다.

| 관측 | 판단 / 동작 |
| --- | --- |
| receipt 없음 또는 정상 outcome 미완성 | OUTSTANDING, 기존 ID 보존, 새 append 없음 |
| 정상 receipt와 원 event의 immutable 의미 불일치 / 다른 Slot 의미 | corruption 실패, 연결/새 event commit 없음 |
| 정상 receipt와 원 event 의미 일치 | 완료. 새 관측에서 다시 WAITING + 적합 + 시작 전 + 가용일 때만 새 ID 허용 |
| 완료지만 현재 기회 없음 | NO_OP, 기존 연결 유지 |
| 최초 기회 또는 완료 후 새 기회 | 새 server UUID, Slot에서 파생한 tenant/참조와 UTC microsecond 시각으로 append |

completion 검증에는 signal/consumer/event/tenant/source Slot/occurredAt/전체 v1 payload 의미와
정확한 route/version/aggregate가 포함된다. 완료 receipt는 Commit 2의 effect + DONE transaction에서만
production으로 작성한다. 이 경로는 delivery state를 읽거나 변경하지 않는다. 미물질화, PENDING,
retry 대기, PROCESSING/claim, DEAD 모두 receipt가 없으면 같은 미완료 ID다. DEAD를 새 ID로 복제하지
않으며 trusted replay만 기존 M3 budget을 다시 열 수 있다.

## Transaction / snapshot / lock 방향

```text
별도 짧은 read-only 관측: immutable stored Slot ownership → 종료
하나의 writable target transaction:
  admission PK insert/current lock
  → 이전 receipt shared current read (있다면 effect commit/rollback 대기)
  → 원 event nonlocking 의미 검증
  → 새 nonlocking Slot/context/capacity/WAITING 관측
  → last_event_id 갱신
  → MANDATORY append: event_boundary → exact active route → event record
  → admission link + event 한 physical commit
```

모두 같은 Product manager/DataSource를 사용한다. ownership read는 link/event를 저장하지 않고 끝나며,
link와 append를 독립 commit하지 않는다. REQUIRES_NEW, transaction suspension, afterCommit, async는 없다.
public request/discovery는 caller transaction을 거부한다. 기존 outer RR snapshot에 참여해 오래된
관측으로 새 기회를 만들거나 이를 피하려고 isolation을 바꾸지 않는다.

target transaction에서는 receipt current lock 전에 consistent read를 실행하지 않는다. 따라서
기다리던 effect가 commit한 뒤 capacity/Entry를 다시 읽는 snapshot은 그 commit 이후다. 최초 page와
ownership 조회의 availability는 admission에 재사용하지 않는다. 그 이후 다른 transaction이 capacity를
차지할 수는 있으며, 실제 reservation 권리는 여전히 M3 promotion의 current guard만 결정한다.

admission table에는 Slot/receipt/event FK가 없다. Slot FK를 먼저 얻고 receipt를 기다리면
promotion의 `receipt → Slot`과 역순이 되고, event FK/locking lookup은 `event_boundary → event`와
역순이 될 수 있다. scoped stored ownership/원 의미 검증과 동일 transaction append가 무결성 경계다.
새 business lock은 append 뒤 얻지 않는다. 기존 M3 effect는 admission row를 요청하지 않으므로
`admission → receipt`의 역방향도 추가하지 않는다.

append 전후 DB failure, 실패를 catch한 MANDATORY rollback-only, link update 실패는 link/event/fence
변경을 함께 rollback한다. 교체 실패는 이전 완료 event 연결을 보존한다. commit 응답 유실/unknown을
확정 rollback으로 바꾸지 않는다. 다음 invocation은 durable link/receipt를 다시 읽으며 새 ID 발급으로
불확실한 기존 요청을 우회하지 않는다.

## Bounded progress / activation

`discovery-batch-size`는 1–1000(기본 32)이고 한 page의 target transaction 수도 이 상한이다.
`request-timeout-seconds`는 1–30(기본 5)이며 read-only scan/ownership query와 각 admission
transaction에 적용한다. SQL 반환 row/target 수와 query 시간의 상한이지 optimizer가 검사할 물리
index record 수의 hard cap이라는 주장은 아니다.

Slot ID 순서는 DB BINARY(16) `ORDER BY id / id > cursor`로 일치시킨다. available Slot page 뒤에서
정확한 WAITING 여부를 재조회한다. NO_OP/OUTSTANDING/target 실패를 지나서도 cursor는 진행한다.
실패는 `Page.failures(slotId,cause)`에 명시하며 정상 no-op/확정 rollback으로 감추지 않는다. 같은 page에서
재시도하지 않는다. 다른 target은 별도 transaction이므로 진행할 수 있다. scan 자체가 실패하면 호출은
실패하며 cursor를 추정하지 않는다. 재시작 시 cursor를 잃어도 durable 연결이 중복 append를 막는다.
유한한 workload를 계속 순회하는 caller를 전제로 하며, scheduling과 반복 실행 계기는 다음 checkpoint다.

#94 등록은 그대로 Entry만 commit한다. 등록 응답 유실이나 process-local wake-up 없이도 WAITING이
다음 scan의 입력이다. lazy due HELD는 advisory capacity가 비어 보일 수 있지만 이 discovery는 stored
expiry/Offer 수렴을 수행하지 않는다. 그 역할은 다음 maintenance checkpoint에 남긴다.

기존 `slotq.waitlist.promotion.enabled=false`를 유지한다. enabled여도 준비된 integration readiness가
없으면 admission은 실패하고, append마다 M3 fence 안에서 request route의 ACTIVE registration을
확인한다. 이 checkpoint는 readiness provider/registration/scheduler를 production에 추가하지 않는다.
test만 두 route의 registration과 readiness를 공급한다.

## MySQL 검증

`WaitlistPromotionRequestIntegrationTests`는 MySQL 8.4 / RR에서 다음을 검증한다.

- 이미 available / release NO_CANDIDATE 이후 WAITING / Resource 활성·적합성 변경 / 새 Slot /
  DEFERRED 이후 새 event로 실제 M3 promotion. discovery 자체의 Reservation/Offer 생성은 0.
- 미물질화/PENDING/CLAIMED/RETRY/DEAD에서 component 교체/재조회해도 같은 ID. exhaustion 후 trusted replay.
- 최초/완료 뒤 multiworker admission: 한 append와 같은 ID의 OUTSTANDING.
- effect receipt가 미commit인 동안 admission 대기 → commit이면 현재 capacity로 NO_OP,
  rollback이면 OUTSTANDING. 기존 early scan availability를 사용하지 않음.
- link/append/append 이후/caught append failure의 원자적 rollback, 교체 rollback, 완료 후 응답 유실 재조회.
- receipt와 원 event 의미/Slot 불일치 거부, 다른 tenant의 event 조회 불가.
- backlog 7 > batch 2, NO_DEMAND/DEAD/손상 prefix 뒤 진행과 reset cursor 재탐색.
- Slot X와 event_boundary X를 외부에서 보유한 실제 contention에서 admission은 business record
  lock 없이 fence만 기다리고, fence를 풀면 Slot X가 남아 있어도 append 완료.
- 다른 Slot admission이 exact tenant/Slot admission lock에 막히지 않음.

lock fixture는 `build/waitlist-request-lock-evidence.txt`에 원자료를 재생성한다. 수정된 C3를 그대로
적용하며 observed 1213은 trace로 application inverse edge/engine physical transient를 구분한다.
[admission/fence 원자료](../experiments/evidence/promotion-request-2026-09-21/ADMISSION_APPEND_FENCE.txt)는
2026-09-21 HEAD `4156053` 위 이 checkpoint source, Java 25 / Gradle 9.7.1 / MySQL 8.4.11 / RR에서
수집했다. 정확한 admission PK의 X record와 event_boundary X 대기만 있고 Slot/Reservation/Allocation/
Entry/Offer record lock은 없다. fixture UUID는 일회용 synthetic 값이다. 별도 외부 Slot X를 계속
보유한 상태에서 fence만 해제했을 때 request append가 commit하는 종료 oracle도 함께 검증했다.
기존 Commit 2의 independent 1213 rollback/retry/DEAD/replay와 capacity-gap opt-in fixture를 변경하지 않는다.
component 교체/response-loss 검증은 실제 JVM crash 또는 DB outage 증거라고 주장하지 않는다.

```powershell
.\gradlew.bat test --tests '*WaitlistPromotionRequestIntegrationTests' --tests '*WaitlistPromotionDeliveryIntegrationTests' --tests '*EventDeliveryIntegrationTests' --tests '*WaitlistRegistrationIntegrationTests' --tests '*BookingCapacityReleaseIntegrationTests' --tests '*WaitlistArchitectureTests' --tests '*EventFoundationArchitectureTests'
```

전체 Backend `test` / `clean build`는 최종 PR gate에서 별도로 수행한다.

2026-09-21 focused 검증 결과(분리 실행, 중복 제외):

| Suite | 성공 |
| --- | ---: |
| WaitlistPromotionRequestIntegrationTests | 39 |
| WaitlistPromotionDeliveryIntegrationTests | 62 |
| EventDeliveryIntegrationTests | 19 |
| WaitlistRegistrationIntegrationTests | 21 |
| BookingCapacityReleaseIntegrationTests | 36 |
| WaitlistArchitectureTests / EventFoundationArchitectureTests | 6 / 9 |

총 192건 성공, 기존 capacity-gap opt-in 진단 1건 제외. 최초 fixture의 Clock bean 이름 충돌을
보정했으며, 다른 Slot 진행 테스트의 제어 query가 tenant 조건 없이 넓은 range를 잠그던 문제를
실제 admission과 같은 exact PK 조건으로 고쳤다. 실패한 해당 검증과 이후 추가/보강한 회귀는
재실행해 성공했다. 이번 checkpoint에서 production application cycle이나 새 1213은 관측하지 않았다.
기존 promotion의 독립 1213 주입 회귀는 함께 통과했다.
