package com.slotq.venue.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
class BookingPolicyId implements Serializable {

    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "venue_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID venueId;

    @Column(name = "policy_version", nullable = false)
    private long policyVersion;

    protected BookingPolicyId() {
    }

    BookingPolicyId(UUID tenantId, UUID venueId, long policyVersion) {
        this.tenantId = tenantId;
        this.venueId = venueId;
        this.policyVersion = policyVersion;
    }

    long policyVersion() {
        return policyVersion;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BookingPolicyId that)) {
            return false;
        }
        return policyVersion == that.policyVersion
            && Objects.equals(tenantId, that.tenantId)
            && Objects.equals(venueId, that.venueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, venueId, policyVersion);
    }
}
