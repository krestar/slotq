# ADR-0005: MySQL reliability record로 HOLD command idempotency 보장

- 상태: `Accepted`
- 결정일: 2026-09-01
- 관련 Issue: [#17](https://github.com/krestar/slotq/issues/17)

## 맥락

`POST /api/v1/venues/{venueId}/reservations/holds`는 client가 성공 응답을 받지 못했을 때
같은 의도를 다시 보내면 별도 Reservation과 Allocation을 만들 수 있었다. Reservation과
Allocation은 이미 하나의 MySQL transaction으로 저장되지만, 성공한 command identity를
기억하는 reliability state가 없으므로 response loss와 같은 key의 동시 요청을 구분할 수
없다.

HOLD response는 시간에 따라 effective `EXPIRED`가 될 수 있다. 따라서 최초 JSON body를
저장해 그대로 돌려주는 방식은 기존 effective-state read 계약과 맞지 않는다. Tenant와
Customer scope도 client 입력이 아니라 저장된 Slot ownership과 `AuthenticatedPrincipal`에서
결정해야 한다.

## 결정

### API와 command identity

- 적용 대상은 HOLD 생성 endpoint 하나다. `Idempotency-Key` header가 없으면 기존 #13의
  one-shot command를 그대로 실행하고 replay 보장을 주장하지 않는다.
- header가 있으면 1자 이상 255자 이하의 visible ASCII(`0x21`–`0x7e`)만 허용한다. 값은
  trim, case folding 또는 다른 정규화를 하지 않는 opaque 값이다. blank, non-ASCII,
  whitespace 포함 또는 길이 초과 값은 command를 실행하기 전에 `400 VALIDATION_FAILED`로
  거부한다.
- namespace는 `(tenantId, authenticated customerPrincipalId, idempotencyKey)`다.
  `tenantId`는 path의 Venue에 속한 저장된 Slot에서 얻고 Customer identity는
  `AuthenticatedPrincipal`에서만 얻는다.
- fingerprint는 정규화된 JSON이나 serialized body hash가 아니라 `venueId`,
  `slotInventoryId`, `partySize` semantic column으로 저장하고 비교한다.
- retention 안의 같은 namespace와 같은 fingerprint는 같은 command다. 다른 fingerprint는
  side effect 없이 `409 IDEMPOTENCY_KEY_REUSED`다.

### Persistence와 동시성

`hold_idempotency_records`를 Business Aggregate가 아닌 Booking application reliability
state로 둔다. Primary key를 namespace 전체로 만들고, 최초 요청은 `IN_PROGRESS` row를
일반 `INSERT`로 획득한다.

같은 namespace의 동시 요청은 InnoDB unique-key insert 경쟁에서 직렬화된다. 승자
transaction이 commit되면 패자의 insert는 duplicate-key가 되고, 패자는 reliability row를
`SELECT ... FOR UPDATE` current read로 확인한다. 승자가 rollback되면 insert도 함께
rollback되므로 기다리던 요청 중 하나가 row를 새로 insert하고 command를 실행한다.
별도 polling, process-local lock, Redis lock은 사용하지 않는다.

승자는 다음 변경을 하나의 Spring/MySQL transaction에서 처리한다.

1. `IN_PROGRESS` reliability row insert
2. Reservation insert
3. CapacityAllocation `units=1` insert
4. reliability row를 ReservationId와 `completedAt`이 있는 `COMPLETED`로 변경

따라서 committed HOLD와 completed result 사이에 commit/crash window가 없다. 어느 단계든
실패하면 세 row가 모두 rollback되고 key가 점유되지 않는다. 정상 경로의 `IN_PROGRESS`
row는 transaction 밖에 commit되지 않으므로 다른 요청은 미완료 row를 관찰하는 대신
database lock에서 최초 transaction의 commit 또는 rollback을 기다린다.

패자는 completed row의 fingerprint를 검증한 뒤 최초 ReservationId로 Reservation과
Allocation을 locking current read한다. MySQL 기본 repeatable-read transaction이 insert
경쟁 전에 읽은 snapshot을 재사용하지 않게 current read를 사용한다. response는 저장된
JSON이 아니라 retry 시점의 주입 `Clock`으로 effective state를 계산해 항상 `201`과 최초
Location, 동일 Reservation identity를 반환한다.

이 row lock은 같은 idempotency namespace의 duplicate result 조회에만 사용한다. 서로
다른 key의 Slot capacity 경합 전략이나 `RESERVATION_STATE_CONFLICT`는 #16의 책임으로
남긴다.

### Retention과 cleanup

- 24시간은 HOLD lifetime과 일반적인 response-loss 재시도보다 충분히 길어 같은 사용자 의도의
  명시적 복구 시간을 제공하면서, Customer 식별자가 포함된 reliability state를 무기한
  보관하지 않는 경계로 선택한다. 더 긴 retry window를 요구하는 근거는 현재 없다.
- retention은 successful command의 `completedAt`부터 24시간이다. `completedAt + 24h`
  미만에는 최초 Reservation identity를 반드시 replay한다.
- 경계 시각(`completedAt + 24h`)부터 같은 key 문자열은 새 command identity로 사용할 수
  있다. 새 요청은 기존 completed row를 lock한 상태에서 삭제하고 새 `IN_PROGRESS` row를
  insert하므로 cleanup 지연이 retention 종료 동작을 늦추지 않는다.
- 매 1시간 실행되는 scheduler가 `COMPLETED`이면서 경계가 지난 row를 오래된 순서로 한 번에
  최대 1,000개 삭제한다. Reservation과 Allocation은 삭제하지 않는다.
- cleanup predicate는 `COMPLETED`와 `completedAt`만 사용한다. `IN_PROGRESS`는 오래됐다는
  이유로 삭제하지 않으므로 실행 중 command를 중복 실행 가능하게 만들지 않는다. Process
  crash는 transaction rollback으로 정리한다.
- #71 Customer UI의 key 보존과 명시적 retry window는 24시간보다 짧아야 한다. retention이
  지난 사용자 의도에는 같은 key를 다시 보내지 않고 새 key를 발급한다.

다음이 관측되면 24시간 retention, 1시간 interval 또는 batch size를 재검토한다.

- 실제 client retry window가 24시간에 근접하거나 이를 넘어야 하는 제품 요구
- reliability row 증가량이나 cleanup lock 시간이 운영 목표를 넘는 경우
- 장시간 transaction, database 장애 또는 connection timeout 때문에 duplicate 대기 시간이
  API timeout budget을 반복해서 넘는 경우

## 대안 검토

### 최초 serialized response 저장

구현은 단순하지만 retry 시점의 effective expiry와 이후 lifecycle state를 반영하지 못해
#13 read 계약을 깨뜨리므로 선택하지 않았다.

### committed IN_PROGRESS lease와 timeout takeover

긴 command를 transaction 밖에서 추적할 수 있지만 HOLD side effect와 completed result
사이에 crash recovery protocol이 추가된다. stale timeout cleanup이 실제 진행 중 command를
중복 실행할 위험도 있어 현재 단일 MySQL transaction에는 불필요하다.

### process-local lock 또는 Redis deduplication

복수 instance와 database commit 원자성을 함께 보장하지 못하거나 별도 분산 reliability
system을 요구한다. 현재 MySQL unique key와 transaction만으로 계약을 충족하므로 선택하지
않았다.

## 결과

- key를 제공한 HOLD retry와 같은-key 동시 요청은 Reservation/Allocation을 하나만 만든다.
- 다른 Customer 또는 다른 Tenant는 같은 key 문자열을 독립적으로 사용할 수 있다.
- invalid key, fingerprint conflict와 rollback은 completed success를 남기지 않는다.
- confirm, cancel, Management command와 Customer UI key lifecycle은 변경하지 않는다.
- idempotency row에는 authenticated Customer principal ID가 포함되므로 access를 Product
  endpoint에 노출하지 않고 retention cleanup으로 제한한다.
