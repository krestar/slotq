package com.slotq.experiments.events;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class EventBoundaryTests {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    @Test
    void joinedOuterRollbackRejectsStandaloneAppendAndJsonRoundTripPreservesMeaning() {
        try (var f = new EventFixture(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            f.schema();
            var e = f.event(80, 1);
            assertThatThrownBy(() -> f.append(e)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> f.tx.executeWithoutResult(outer -> {
                f.produce(EventFixture.Boundary.DURABLE, e, "NONE");
                assertThat(f.snapshot(e)).containsEntry("business", 1).containsEntry("durable", 1);
                throw new IllegalStateException("outer rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(f.snapshot(e)).containsEntry("business", 0).containsEntry("durable", 0);
            f.produce(EventFixture.Boundary.DURABLE, e, "NONE");
            f.tx.executeWithoutResult(s -> f.effect(e, "NONE"));
            f.tx.executeWithoutResult(s -> f.effect(f.storedEvents().getFirst(), "NONE"));
            assertThat(f.snapshot(e)).containsEntry("effects", 1);
        }
    }
}
