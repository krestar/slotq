# Database Migration Compatibility

SlotQ Product schema의 source of truth는
`backend/src/main/resources/db/migration/`의 Flyway SQL이다. Hibernate는 schema를
생성하거나 갱신하지 않으며 `ddl-auto=validate`로 migration 결과와 JPA mapping의
호환성만 확인한다.

## V2 Tenant, Venue and Booking Policy

`V2__create_tenant_venue_policy.sql`은 기존 V1 database에 `tenants`, `venues`,
`venue_operating_hours`, `booking_policies`를 추가하는 additive migration이다. 기존
Product table이나 column을 변경하지 않으므로 V1 실행 환경과 데이터에는 파괴적
호환성 영향이 없다.

모든 Venue 구성 row는 `tenant_id NOT NULL`을 가지며, Venue 하위 table은
`(tenant_id, venue_id)` 복합 외래키로 same-tenant 참조를 강제한다. Booking Policy는
Venue별 version을 복합 primary key에 포함해 이전 version을 보존한다. 영업시간은 열린
요일만 row로 저장하고 row가 없는 요일을 휴무로 해석한다.

Flyway production migration을 되돌리는 undo migration은 제공하지 않는다. V2 rollback은
네 table과 그 안의 Tenant/Venue/Policy 데이터를 삭제해야 하는 비가역적 변경이므로,
배포 후 문제는 백업 복원 또는 더 높은 version의 forward-fix migration으로 처리한다.
Production 적용 전에는 database backup과 복원 가능성을 확인한다.

## V7 HOLD Idempotency Reliability State

`V7__create_hold_idempotency.sql`은 기존 Product table을 변경하지 않고
`hold_idempotency_records`를 추가하는 additive migration이다. Primary key는
`(tenant_id, customer_principal_id, idempotency_key)`이며 key는 255자 ASCII binary
collation으로 대소문자와 byte 값을 그대로 구분한다. `venue_id`, `slot_inventory_id`,
`party_size`는 semantic request fingerprint이고 completed row는 최초 `reservation_id`를
참조한다.

Reservation, CapacityAllocation과 reliability row는 같은 MySQL transaction에서 함께
commit 또는 rollback한다. cleanup index `(state, completed_at)`는 retention이 지난
`COMPLETED` row의 bounded delete만 지원하며 `IN_PROGRESS` row는 cleanup 대상이 아니다.
V7 rollback은 idempotency replay 이력을 잃으므로 production에서는 table을 drop하지 않고
더 높은 version의 forward-fix migration으로 처리한다.

## V8 HOLD Idempotency Reservation Scope

`V8__scope_hold_idempotency_reservation_reference.sql`은 V7의
`reservation_id -> reservations(id)` 단일 외래키를
`(tenant_id, venue_id, reservation_id) -> reservations(tenant_id, venue_id, id)` 복합
외래키로 교체한다. 기존 정상 V7 row는 같은 값을 유지하며, completed reliability row가
다른 Tenant 또는 Venue의 Reservation identity를 가리키는 직접 persistence를 DB에서
거부한다.

Application의 namespace, fingerprint, retention과 transaction 순서는 변경하지 않는다.
Production rollback은 scoped constraint를 제거하지 않고 더 높은 version의 forward-fix
migration으로 처리한다.

## V9 Transactional Event Foundation

`V9__create_transactional_events.sql`은 기존 Product table을 변경하지 않는 additive migration이다.
`event_boundary`, `event_records`, `event_registrations`, `event_discovery`, `event_deliveries`,
`event_replay_audit`를 추가한다. 기존 Booking/Reservation을 event로 backfill하지 않으며
scheduler도 기본 disabled다.

UUID는 `BINARY(16)`, occurred/record/lease/retry time은 UTC `DATETIME(6)`이다. route identifier는
100자 ASCII binary equality와 grammar CHECK를 사용한다. payload는 exact decimal 의미를
유지하는 canonical `MEDIUMTEXT`와 `JSON_VALID` CHECK이며 native JSON 숫자 normalization에
의존하지 않는다. `(tenant_id,event_id)` 복합 FK가 cross-tenant delivery를 막고 original
registration UUID를 replay audit까지 보존한다.

Append/cutover의 `event_boundary` singleton lock은 outer transaction까지 유지하며 sequence
증가도 rollback된다. discovery cursor와 delivery materialization은 별도의 한 transaction이다.
automatic retention/cleanup은 없으며 기존 HOLD idempotency retention과 연결하지 않는다.

V9 rollback은 event·target·recovery 이력을 잃을 수 있으므로 table 삭제 대신 backup 복원 또는
상위 version의 forward-fix로 처리한다. 상세 runtime 설정과 후속 ownership은
[Event Delivery](event-delivery.md)에 기록한다.

## V10 Waitlist Registration

`V10__create_waitlist_registration.sql`은 normalized Demand, WaitlistEntry와 등록 요청 결과를
추가한다. Demand identity는 `(tenant, venue, startsAt, endsAt, partySize)`이며 Demand row는
Queue 전체가 아닌 같은 Customer 수요의 등록·취소만 직렬화하는 좁은 경계다.

stored `WAITING`/`OFFERED`만 generated active-membership key에 참여하므로 Customer와 Demand당
활성 Entry를 하나로 제한하면서 terminal 이후 새 Entry를 허용한다. 등록 요청은 tenant,
Customer, UUID key scope와 원본 Venue/Slot/partySize fingerprint, 최초 Entry/status를 보존한다.
Demand/Entry/result는 같은 transaction에서 commit 또는 rollback되며 자동 cleanup은 없다.
Entry, 원본 Slot과 Customer 참조는 scoped foreign key로 보호한다. V10 rollback은 Queue 및
response-loss 복구 이력을 잃으므로 production에서는 상위 migration으로 처리한다.

## V11 Waitlist Offer Lifecycle

`V11__create_waitlist_offers.sql`은 promotional Reservation identity와 최초 CONFIRM 증거를
Reservation에 추가하고, Entry별 Offer 생명주기를 저장하는 `waitlist_offers`를 추가한다.
Offer, Entry, Reservation은 모두 Tenant/Venue scope 복합 외래키로 연결되며 Entry당 Offer와
Reservation당 Offer는 각각 하나로 제한한다.

promotional HOLD 생성 시 Reservation, CapacityAllocation, Offer와 Entry의 `OFFERED` 전이는 같은
transaction에서 commit 또는 rollback한다. promotional request identity는 Entry ID이고 일반
Customer HOLD의 Idempotency-Key namespace와 공유하지 않는다. `promotional_confirmed`는 최초
CONFIRM 완료를 보존하는 durable evidence이며 이후 일반 Reservation cancel과 구분한다.

V11 rollback은 활성 Offer와 수락 증거를 잃고 기존 Reservation column도 제거해야 하므로
production에서는 migration을 되돌리지 않고 backup 복원 또는 상위 version의 forward-fix로
처리한다.

## V12 Waitlist Promotion Effect

`V12__create_waitlist_promotion_effect.sql`은 Waitlist 소유의 event-level receipt와
테스트용 Offer 알림 요청 접수를 추가한다. receipt PK는 `(tenant_id, consumer_id, event_id)`,
notification PK는 `(tenant_id, offer_id, request_type)`이다. registration generation/token은
logical effect identity에 포함하지 않는다. v1 immutable 의미를 typed field로 보존한다.

receipt claim은 Slot 전에 insert/lock하지만 아직 NULL인 effect FK 때문에 business parent를
선점하지 않는다. receipt completion과 notification은 같은 Tenant/Venue/Entry/Reservation/Offer
조합을 scoped FK로 참조한다. NULL outcome은 transaction 안의 미완료 claim일 뿐이며 정상
production 경로는 반드시 outcome을 완성하거나 전체 rollback한다. 별도 request 완료 ledger는 없다.

Entry의 `(demand_id,state,joined_at,id)` index는 eligible Demand별 WAITING head의 current-read를
지원한다. 기존 Offer에는 effect 참조용 scoped unique key만 추가한다. 기존 Booking capacity
table/authority, Offer/Customer API, 기존 data의 의미는 바꾸지 않는다.

V12만 적용해도 producer/worker를 활성화하지 않는다. receipt/notification/event 이력 cleanup이나
undo migration은 없으며 문제 발생 시 backup 복원 또는 상위 version의 forward-fix를 사용한다.

## V13 Waitlist Promotion Request Admission

`V13__create_waitlist_promotion_requests.sql`은 `(tenant_id,slot_inventory_id)`당 마지막 request
event ID 연결만 추가한다. nullable last_event_id는 아직 발행하지 않은 admission anchor이며 별도
완료/실패/retry 상태는 없다. 기존 V12 receipt와 원 event의 의미가 완료 oracle이다.

Slot/receipt/event FK는 두지 않는다. receipt 이전 Slot FK lock 또는 event_boundary 이전 event FK
lock을 추가하지 않으며, stored Slot ownership + 같은 target transaction의 link/append가 무결성을
보장한다. [request transaction/lock 경계](waitlist-promotion-requests.md)를 따른다.
기존 Booking/Offer/capacity schema와 데이터는 변경하지 않고 migration만으로 runtime을 활성화하지 않는다.
기록 cleanup/undo migration은 제공하지 않으며 문제 발생 시 상위 version의 forward-fix를 사용한다.

## V14 Maintenance Backlog Indexes

`V14__index_maintenance_backlogs.sql`은 reservations / waitlist_offers / waitlist_entries에
`(state,id)` nonunique index를 추가한다. [maintenance](waitlist-maintenance.md)의 nonlocking bounded
keyset scan을 지원하며 capacity authority, state/unique/FK 의미, 기존 current-read query를 바꾸지 않는다.
index 추가만으로 worker/scheduler/producer를 활성화하지 않는다. data rewrite/삭제/undo migration은 없다.
