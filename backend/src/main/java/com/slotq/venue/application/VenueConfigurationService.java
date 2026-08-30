package com.slotq.venue.application;

import java.time.Clock;
import java.time.ZoneId;
import java.util.NoSuchElementException;
import java.util.Objects;

import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.PolicyDeadlines;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;
import com.slotq.venue.domain.VenueStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class VenueConfigurationService implements VenueConfigurationUseCase {

    private final VenueRepository venueRepository;
    private final TenantUseCase tenantUseCase;
    private final Clock clock;

    VenueConfigurationService(VenueRepository venueRepository, TenantUseCase tenantUseCase, Clock clock) {
        this.venueRepository = venueRepository;
        this.tenantUseCase = tenantUseCase;
        this.clock = clock;
    }

    @Override
    public Venue createVenue(CreateVenue command) {
        Objects.requireNonNull(command, "command must not be null");
        tenantUseCase.getTenant(command.tenantId());
        BookingPolicy initialPolicy = new BookingPolicy(1, command.initialPolicy(), clock.instant());
        Venue venue = new Venue(
            VenueId.newId(),
            command.tenantId(),
            command.name(),
            VenueStatus.ACTIVE,
            parseTimezone(command.timezone()),
            command.operatingHours(),
            initialPolicy
        );
        venueRepository.create(venue);
        return venue;
    }

    @Override
    @Transactional(readOnly = true)
    public Venue getVenue(TenantId tenantId, VenueId venueId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(venueId, "venueId must not be null");
        return venueRepository.find(tenantId, venueId).orElseThrow(() -> notFound(tenantId, venueId));
    }

    @Override
    public Venue updateVenue(UpdateVenue command) {
        Objects.requireNonNull(command, "command must not be null");
        Venue venue = getVenue(command.tenantId(), command.venueId()).withConfiguration(
            command.name(),
            Objects.requireNonNull(command.status(), "status must not be null"),
            parseTimezone(command.timezone()),
            Objects.requireNonNull(command.operatingHours(), "operatingHours must not be null")
        );
        venueRepository.updateConfiguration(venue);
        return venue;
    }

    @Override
    public BookingPolicy updateBookingPolicy(UpdateBookingPolicy command) {
        Objects.requireNonNull(command, "command must not be null");
        Venue venue = venueRepository.findForUpdate(command.tenantId(), command.venueId())
            .orElseThrow(() -> notFound(command.tenantId(), command.venueId()));
        BookingPolicy policy = new BookingPolicy(
            Math.addExact(venue.currentPolicy().version(), 1),
            command.terms(),
            clock.instant()
        );
        venueRepository.appendPolicy(command.tenantId(), command.venueId(), policy);
        return policy;
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyDeadlines applyCurrentPolicy(TenantId tenantId, VenueId venueId, java.time.Instant startsAt) {
        return getVenue(tenantId, venueId).currentPolicy().applyTo(startsAt, clock);
    }

    private ZoneId parseTimezone(String timezone) {
        String zoneId = Objects.requireNonNull(timezone, "timezone must not be null");
        ZoneId parsed = ZoneId.of(zoneId);
        if (!ZoneId.getAvailableZoneIds().contains(zoneId)) {
            throw new IllegalArgumentException("timezone must be an IANA Zone ID: " + zoneId);
        }
        return parsed;
    }

    private NoSuchElementException notFound(TenantId tenantId, VenueId venueId) {
        return new NoSuchElementException(
            "Venue not found for tenant " + tenantId.value() + ": " + venueId.value()
        );
    }
}
