package com.slotq.booking.persistence;

import java.time.Instant;
import java.util.UUID;

import com.slotq.booking.domain.ReservationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservations")
class ReservationJpaEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;
    @Column(name = "venue_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID venueId;
    @Column(name = "resource_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID resourceId;
    @Column(name = "slot_inventory_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID slotInventoryId;
    @Column(name = "customer_principal_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID customerPrincipalId;
    @Column(name = "party_size", nullable = false)
    private int partySize;
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ReservationState state;
    @Column(name = "applied_policy_version", nullable = false)
    private long appliedPolicyVersion;
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "cancel_allowed_until", nullable = false)
    private Instant cancelAllowedUntil;
    @Column(name = "no_show_eligible_at", nullable = false)
    private Instant noShowEligibleAt;

    protected ReservationJpaEntity() { }

    ReservationJpaEntity(UUID id, UUID tenantId, UUID venueId, UUID resourceId, UUID slotInventoryId,
                         UUID customerPrincipalId, int partySize, ReservationState state,
                         long appliedPolicyVersion, Instant startsAt, Instant expiresAt,
                         Instant cancelAllowedUntil, Instant noShowEligibleAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.venueId = venueId;
        this.resourceId = resourceId;
        this.slotInventoryId = slotInventoryId;
        this.customerPrincipalId = customerPrincipalId;
        this.partySize = partySize;
        this.state = state;
        this.appliedPolicyVersion = appliedPolicyVersion;
        this.startsAt = startsAt;
        this.expiresAt = expiresAt;
        this.cancelAllowedUntil = cancelAllowedUntil;
        this.noShowEligibleAt = noShowEligibleAt;
    }

    UUID id() { return id; }
    UUID tenantId() { return tenantId; }
    UUID venueId() { return venueId; }
    UUID resourceId() { return resourceId; }
    UUID slotInventoryId() { return slotInventoryId; }
    UUID customerPrincipalId() { return customerPrincipalId; }
    int partySize() { return partySize; }
    ReservationState state() { return state; }
    long appliedPolicyVersion() { return appliedPolicyVersion; }
    Instant startsAt() { return startsAt; }
    Instant expiresAt() { return expiresAt; }
    Instant cancelAllowedUntil() { return cancelAllowedUntil; }
    Instant noShowEligibleAt() { return noShowEligibleAt; }
}
