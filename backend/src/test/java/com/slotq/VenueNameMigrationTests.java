package com.slotq;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalTime;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class VenueNameMigrationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq_migration");

    @Test
    void backfillsExistingVenueBeforeEnforcingNonblankName() throws SQLException {
        Flyway v5 = flyway().target(MigrationVersion.fromVersion("5")).load();
        v5.migrate();

        try (var connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); var statement = connection.createStatement()) {
            statement.executeUpdate(
                "INSERT INTO tenants (id, status) "
                    + "VALUES (UNHEX('10000000000000000000000000000001'), 'ACTIVE')"
            );
            statement.executeUpdate(
                "INSERT INTO venues (id, tenant_id, status, timezone) VALUES ("
                    + "UNHEX('20000000000000000000000000000001'), "
                    + "UNHEX('10000000000000000000000000000001'), 'ACTIVE', 'Asia/Seoul')"
            );
            statement.executeUpdate(
                "INSERT INTO venue_operating_hours "
                    + "(tenant_id, venue_id, day_of_week, opens_at, closes_at) VALUES ("
                    + "UNHEX('10000000000000000000000000000001'), "
                    + "UNHEX('20000000000000000000000000000001'), 1, '09:00:00', '18:00:00')"
            );
            statement.executeUpdate(
                "INSERT INTO booking_policies (tenant_id, venue_id, policy_version, "
                    + "slot_duration_minutes, hold_duration_minutes, cancellation_cutoff_minutes, "
                    + "no_show_grace_minutes, created_at) VALUES ("
                    + "UNHEX('10000000000000000000000000000001'), "
                    + "UNHEX('20000000000000000000000000000001'), 1, 30, 5, 60, 15, "
                    + "'2026-08-29 00:00:00')"
            );
        }

        flyway().load().migrate();

        try (var connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery(
                "SELECT name, status, timezone FROM venues "
                    + "WHERE id = UNHEX('20000000000000000000000000000001')"
            )) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("name"))
                    .isEqualTo("Venue 20000000000000000000000000000001");
                assertThat(result.getString("status")).isEqualTo("ACTIVE");
                assertThat(result.getString("timezone")).isEqualTo("Asia/Seoul");
            }
            try (var result = statement.executeQuery(
                "SELECT opens_at, closes_at FROM venue_operating_hours "
                    + "WHERE venue_id = UNHEX('20000000000000000000000000000001')"
            )) {
                assertThat(result.next()).isTrue();
                assertThat(result.getTime("opens_at").toLocalTime()).isEqualTo(LocalTime.of(9, 0));
                assertThat(result.getTime("closes_at").toLocalTime()).isEqualTo(LocalTime.of(18, 0));
            }
            try (var result = statement.executeQuery(
                "SELECT policy_version, slot_duration_minutes, hold_duration_minutes, "
                    + "cancellation_cutoff_minutes, no_show_grace_minutes FROM booking_policies "
                    + "WHERE venue_id = UNHEX('20000000000000000000000000000001')"
            )) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("policy_version")).isEqualTo(1L);
                assertThat(result.getInt("slot_duration_minutes")).isEqualTo(30);
                assertThat(result.getInt("hold_duration_minutes")).isEqualTo(5);
                assertThat(result.getInt("cancellation_cutoff_minutes")).isEqualTo(60);
                assertThat(result.getInt("no_show_grace_minutes")).isEqualTo(15);
            }

            assertThatThrownBy(() -> statement.executeUpdate(
                "INSERT INTO venues (id, tenant_id, name, status, timezone) VALUES ("
                    + "UNHEX('20000000000000000000000000000002'), "
                    + "UNHEX('10000000000000000000000000000001'), '   ', 'ACTIVE', 'UTC')"
            )).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                "INSERT INTO venues (id, tenant_id, status, timezone) VALUES ("
                    + "UNHEX('20000000000000000000000000000003'), "
                    + "UNHEX('10000000000000000000000000000001'), 'ACTIVE', 'UTC')"
            )).isInstanceOf(SQLException.class);
        }
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
