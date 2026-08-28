package com.slotq.venue.persistence;

import java.time.DayOfWeek;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.slotq.venue.domain.DailyOperatingHours;

@Entity
@Table(name = "venue_operating_hours")
class OperatingHoursJpaEntity {

    @EmbeddedId
    private OperatingHoursId id;

    @Column(name = "opens_at", nullable = false)
    private LocalTime opensAt;

    @Column(name = "closes_at", nullable = false)
    private LocalTime closesAt;

    protected OperatingHoursJpaEntity() {
    }

    OperatingHoursJpaEntity(OperatingHoursId id, LocalTime opensAt, LocalTime closesAt) {
        this.id = id;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
    }

    DayOfWeek dayOfWeek() {
        return DayOfWeek.of(id.dayOfWeek());
    }

    DailyOperatingHours toDomain() {
        return new DailyOperatingHours(opensAt, closesAt);
    }
}
