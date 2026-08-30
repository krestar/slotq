package com.slotq.venue.persistence;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.slotq.availability.application.PublicVenueQuery;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Component;

@Component
class PublicVenueQueryAdapter implements PublicVenueQuery {

    private final VenueSpringDataRepository repository;

    PublicVenueQueryAdapter(VenueSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<PublicVenue> findAllActive() {
        return repository.findAllActivePublicVenues().stream().map(this::toPublicVenue).toList();
    }

    @Override
    public Optional<PublicVenue> findActive(VenueId venueId) {
        return repository.findActivePublicVenueById(venueId.value()).map(this::toPublicVenue);
    }

    private PublicVenue toPublicVenue(VenueSpringDataRepository.PublicVenueRow row) {
        return new PublicVenue(
            new TenantId(row.getTenantId()), new VenueId(row.getId()),
            row.getName(), ZoneId.of(row.getTimezone())
        );
    }
}
