package com.slotq.venue.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.BookingPolicyTerms;

@Entity
@Table(name = "booking_policies")
class BookingPolicyJpaEntity {

    @EmbeddedId
    private BookingPolicyId id;

    @Column(name = "slot_duration_minutes", nullable = false)
    private int slotDurationMinutes;

    @Column(name = "hold_duration_minutes", nullable = false)
    private int holdDurationMinutes;

    @Column(name = "cancellation_cutoff_minutes", nullable = false)
    private int cancellationCutoffMinutes;

    @Column(name = "no_show_grace_minutes", nullable = false)
    private int noShowGraceMinutes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BookingPolicyJpaEntity() {
    }

    BookingPolicyJpaEntity(BookingPolicyId id, BookingPolicy policy) {
        this.id = id;
        this.slotDurationMinutes = policy.terms().slotDurationMinutes();
        this.holdDurationMinutes = policy.terms().holdDurationMinutes();
        this.cancellationCutoffMinutes = policy.terms().cancellationCutoffMinutes();
        this.noShowGraceMinutes = policy.terms().noShowGraceMinutes();
        this.createdAt = policy.createdAt();
    }

    BookingPolicy toDomain() {
        return new BookingPolicy(
            id.policyVersion(),
            new BookingPolicyTerms(
                slotDurationMinutes,
                holdDurationMinutes,
                cancellationCutoffMinutes,
                noShowGraceMinutes
            ),
            createdAt
        );
    }
}
