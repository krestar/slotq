package com.slotq.auth.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;

import com.slotq.auth.application.AccessControlProvisioning;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class DevAuthFixtureDataInitializerTests {

    @Test
    void uses_a_mysql_timestamp_safe_policy_creation_time() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AccessControlProvisioning provisioning = mock(AccessControlProvisioning.class);
        DevAuthFixtureDataInitializer initializer = new DevAuthFixtureDataInitializer(
            jdbcTemplate, provisioning
        );

        initializer.run(null);

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).update(contains("INSERT IGNORE INTO booking_policies"),
            arguments.capture());
        assertThat(arguments.getAllValues())
            .extracting(values -> (Timestamp) values[2])
            .containsOnly(Timestamp.from(Instant.EPOCH.plusSeconds(1)))
            .allSatisfy(timestamp -> assertThat(timestamp.toInstant()).isAfter(Instant.EPOCH));
    }
}
