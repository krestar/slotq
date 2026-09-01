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
