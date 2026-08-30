package com.slotq.availability.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.booking.application.ReservationRepository;
import com.slotq.booking.application.SlotInventoryRepository;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.venue.application.ResourceRepository;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicAvailabilityService implements PublicAvailabilityUseCase {

    private final PublicVenueQuery venueQuery;
    private final ResourceRepository resourceRepository;
    private final SlotInventoryRepository slotRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    PublicAvailabilityService(
        PublicVenueQuery venueQuery,
        ResourceRepository resourceRepository,
        SlotInventoryRepository slotRepository,
        ReservationRepository reservationRepository,
        Clock clock
    ) {
        this.venueQuery = venueQuery;
        this.resourceRepository = resourceRepository;
        this.slotRepository = slotRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VenueSummary> getVenues() {
        return venueQuery.findAllActive().stream()
            .map(venue -> new VenueSummary(venue.venueId(), venue.name(), venue.timezone()))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Availability getAvailability(VenueId venueId, LocalDate date, int partySize) {
        if (date == null) {
            throw validation("date", "must not be null");
        }
        if (partySize < 1) {
            throw validation("partySize", "must be positive");
        }

        PublicVenueQuery.PublicVenue venue = venueQuery.findActive(venueId)
            .orElseThrow(ResourceNotFoundException::new);
        DateWindow window = dateWindow(date, venue);
        Instant verificationNow = clock.instant();

        Map<ResourceId, Resource> candidates = resourceRepository
            .findAll(venue.tenantId(), venue.venueId()).stream()
            .filter(resource -> resource.status() == ResourceStatus.ACTIVE)
            .filter(resource -> partySize <= resource.seatingCapacity())
            .collect(Collectors.toMap(Resource::id, Function.identity()));

        List<AvailabilityItem> items = slotRepository.findAll(
            venue.tenantId(), venue.venueId(), window.startsAt(), window.endsAt()
        ).stream()
            .filter(slot -> slot.startsAt().isAfter(verificationNow))
            .filter(slot -> candidates.containsKey(slot.resourceId()))
            .map(slot -> item(venue, candidates.get(slot.resourceId()), slot, verificationNow))
            .sorted(Comparator.comparing(AvailabilityItem::startsAt)
                .thenComparing(item -> item.resourceId().value()))
            .toList();

        return new Availability(venue.venueId(), venue.timezone(), date, items);
    }

    private AvailabilityItem item(
        PublicVenueQuery.PublicVenue venue,
        Resource resource,
        SlotInventory slot,
        Instant verificationNow
    ) {
        int occupied = reservationRepository.existsEffectiveCapacityConsumer(
            venue.tenantId(), venue.venueId(), resource.id(), slot.id(), verificationNow
        ) ? 1 : 0;
        int available = slot.capacity() - occupied;
        return new AvailabilityItem(
            slot.id(), resource.id(), resource.name(), slot.startsAt(), slot.endsAt(),
            resource.seatingCapacity(), slot.capacity(), occupied, available
        );
    }

    private DateWindow dateWindow(LocalDate date, PublicVenueQuery.PublicVenue venue) {
        try {
            return new DateWindow(
                date.atStartOfDay(venue.timezone()).toInstant(),
                date.plusDays(1).atStartOfDay(venue.timezone()).toInstant()
            );
        } catch (DateTimeException exception) {
            throw validation("date", "must be a valid local date");
        }
    }

    private AvailabilityValidationException validation(String field, String detail) {
        return new AvailabilityValidationException(Map.of(field, detail));
    }

    private record DateWindow(Instant startsAt, Instant endsAt) { }
}
