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
