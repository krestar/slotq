package com.slotq;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class HoldIdempotencyScopeMigrationTests {

    private static final String TENANT_A = "10000000000000000000000000000001";
    private static final String TENANT_B = "10000000000000000000000000000002";
    private static final String VENUE_A = "20000000000000000000000000000001";
    private static final String VENUE_A_SECOND = "20000000000000000000000000000002";
    private static final String VENUE_B = "20000000000000000000000000000003";
    private static final String RESOURCE_A = "30000000000000000000000000000001";
    private static final String RESOURCE_A_SECOND = "30000000000000000000000000000002";
    private static final String RESOURCE_B = "30000000000000000000000000000003";
    private static final String SLOT_A = "40000000000000000000000000000001";
    private static final String SLOT_A_SECOND = "40000000000000000000000000000002";
    private static final String SLOT_B = "40000000000000000000000000000003";
    private static final String CUSTOMER = "50000000000000000000000000000001";
    private static final String RESERVATION_A = "60000000000000000000000000000001";
    private static final String RESERVATION_A_SECOND = "60000000000000000000000000000002";
    private static final String RESERVATION_B = "60000000000000000000000000000003";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq_hold_idempotency_scope_migration");

    @Test
    void migratesV7RowsAndRejectsCrossScopeReservationReferences() throws SQLException {
        flyway().target(MigrationVersion.fromVersion("7")).load().migrate();

        try (var connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); var statement = connection.createStatement()) {
            seedV7Scopes(statement);
            statement.executeUpdate(reliabilityInsert(
                "before-v8", TENANT_A, VENUE_A, SLOT_A, RESERVATION_A
            ));
        }

        Flyway current = flyway().load();
        current.migrate();
        assertThat(current.info().current().getVersion().toString()).isEqualTo("10");

        try (var connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); var statement = connection.createStatement()) {
            assertThat(countReliabilityRows(statement, "before-v8")).isEqualTo(1);

            statement.executeUpdate(reliabilityInsert(
                "same-scope", TENANT_A, VENUE_A, SLOT_A, RESERVATION_A
            ));
            assertThat(countReliabilityRows(statement, "same-scope")).isEqualTo(1);

            assertThatThrownBy(() -> statement.executeUpdate(reliabilityInsert(
                "cross-venue", TENANT_A, VENUE_A, SLOT_A, RESERVATION_A_SECOND
            ))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(reliabilityInsert(
                "cross-tenant", TENANT_A, VENUE_A, SLOT_A, RESERVATION_B
            ))).isInstanceOf(SQLException.class);
        }
    }

    private void seedV7Scopes(Statement statement) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO tenants (id, status) VALUES
              (UNHEX('%s'), 'ACTIVE'),
              (UNHEX('%s'), 'ACTIVE')
            """.formatted(TENANT_A, TENANT_B));
        statement.executeUpdate("""
            INSERT INTO venues (id, tenant_id, name, status, timezone) VALUES
              (UNHEX('%s'), UNHEX('%s'), 'Venue A', 'ACTIVE', 'UTC'),
              (UNHEX('%s'), UNHEX('%s'), 'Venue A Second', 'ACTIVE', 'UTC'),
              (UNHEX('%s'), UNHEX('%s'), 'Venue B', 'ACTIVE', 'UTC')
            """.formatted(
                VENUE_A, TENANT_A, VENUE_A_SECOND, TENANT_A, VENUE_B, TENANT_B
            ));
        statement.executeUpdate("""
            INSERT INTO booking_policies (
              tenant_id, venue_id, policy_version, slot_duration_minutes,
              hold_duration_minutes, cancellation_cutoff_minutes,
              no_show_grace_minutes, created_at
            ) VALUES
              (UNHEX('%s'), UNHEX('%s'), 1, 30, 5, 0, 0, '2026-09-08 00:00:00'),
              (UNHEX('%s'), UNHEX('%s'), 1, 30, 5, 0, 0, '2026-09-08 00:00:00'),
              (UNHEX('%s'), UNHEX('%s'), 1, 30, 5, 0, 0, '2026-09-08 00:00:00')
            """.formatted(
                TENANT_A, VENUE_A, TENANT_A, VENUE_A_SECOND, TENANT_B, VENUE_B
            ));
        statement.executeUpdate("""
            INSERT INTO resources (
              id, tenant_id, venue_id, type, name, seating_capacity, status
            ) VALUES
              (UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), 'TABLE', 'Table A', 2, 'ACTIVE'),
              (UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), 'TABLE', 'Table A Second', 2, 'ACTIVE'),
              (UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), 'TABLE', 'Table B', 2, 'ACTIVE')
            """.formatted(
                RESOURCE_A, TENANT_A, VENUE_A,
                RESOURCE_A_SECOND, TENANT_A, VENUE_A_SECOND,
                RESOURCE_B, TENANT_B, VENUE_B
            ));
        statement.executeUpdate("""
            INSERT INTO slot_inventories (
              id, tenant_id, venue_id, resource_id, starts_at, ends_at,
              capacity, applied_policy_version
            ) VALUES
              (UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), UNHEX('%s'),
               '2030-01-01 10:00:00', '2030-01-01 10:30:00', 1, 1),
              (UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), UNHEX('%s'),
               '2030-01-01 11:00:00', '2030-01-01 11:30:00', 1, 1),
              (UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), UNHEX('%s'),
               '2030-01-01 12:00:00', '2030-01-01 12:30:00', 1, 1)
            """.formatted(
                SLOT_A, TENANT_A, VENUE_A, RESOURCE_A,
                SLOT_A_SECOND, TENANT_A, VENUE_A_SECOND, RESOURCE_A_SECOND,
                SLOT_B, TENANT_B, VENUE_B, RESOURCE_B
            ));
        statement.executeUpdate(
            "INSERT INTO auth_principals (id) VALUES (UNHEX('" + CUSTOMER + "'))"
        );
        statement.executeUpdate("""
            INSERT INTO reservations (
              id, tenant_id, venue_id, resource_id, slot_inventory_id,
              customer_principal_id, party_size, state, applied_policy_version,
              starts_at, expires_at, cancel_allowed_until, no_show_eligible_at
            ) VALUES
              (UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), UNHEX('%s'),
               UNHEX('%s'), 2, 'HELD', 1, '2030-01-01 10:00:00',
               '2030-01-01 09:55:00', '2030-01-01 09:00:00', '2030-01-01 10:15:00'),
              (UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), UNHEX('%s'),
               UNHEX('%s'), 2, 'HELD', 1, '2030-01-01 11:00:00',
               '2030-01-01 10:55:00', '2030-01-01 10:00:00', '2030-01-01 11:15:00'),
              (UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), UNHEX('%s'), UNHEX('%s'),
               UNHEX('%s'), 2, 'HELD', 1, '2030-01-01 12:00:00',
               '2030-01-01 11:55:00', '2030-01-01 11:00:00', '2030-01-01 12:15:00')
            """.formatted(
                RESERVATION_A, TENANT_A, VENUE_A, RESOURCE_A, SLOT_A, CUSTOMER,
                RESERVATION_A_SECOND, TENANT_A, VENUE_A_SECOND, RESOURCE_A_SECOND,
                SLOT_A_SECOND, CUSTOMER,
                RESERVATION_B, TENANT_B, VENUE_B, RESOURCE_B, SLOT_B, CUSTOMER
            ));
    }

    private String reliabilityInsert(String key, String tenant, String venue,
                                     String slot, String reservation) {
        return """
            INSERT INTO hold_idempotency_records (
              tenant_id, customer_principal_id, idempotency_key, venue_id,
              slot_inventory_id, party_size, state, reservation_id, started_at, completed_at
            ) VALUES (
              UNHEX('%s'), UNHEX('%s'), '%s', UNHEX('%s'),
              UNHEX('%s'), 2, 'COMPLETED', UNHEX('%s'),
              '2026-09-08 00:00:00', '2026-09-08 00:00:01'
            )
            """.formatted(tenant, CUSTOMER, key, venue, slot, reservation);
    }

    private long countReliabilityRows(Statement statement, String key) throws SQLException {
        try (var result = statement.executeQuery(
            "SELECT COUNT(*) FROM hold_idempotency_records WHERE idempotency_key = '" + key + "'"
        )) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
