package com.slotq.availability.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.slotq.availability.application.PublicAvailabilityUseCase;
import com.slotq.venue.domain.VenueId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/venues")
class PublicAvailabilityController {

    private final PublicAvailabilityUseCase useCase;

    PublicAvailabilityController(PublicAvailabilityUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    List<VenueResponse> getVenues() {
        return useCase.getVenues().stream().map(venue -> new VenueResponse(
            venue.venueId().value(), venue.name(), venue.timezone().getId()
        )).toList();
    }

    @GetMapping("/{venueId}/availability")
    ResponseEntity<AvailabilityResponse> getAvailability(
        @PathVariable UUID venueId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam int partySize
    ) {
        PublicAvailabilityUseCase.Availability availability = useCase.getAvailability(
            new VenueId(venueId), date, partySize
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new AvailabilityResponse(
                availability.venueId().value(), availability.timezone().getId(),
                availability.date(), availability.items().stream().map(item ->
                    new AvailabilityItemResponse(
                        item.slotInventoryId().value(), item.resourceId().value(),
                        item.resourceName(), item.startsAt(), item.endsAt(),
                        item.seatingCapacity(), item.capacity(), item.occupied(), item.available()
                    )).toList()
            ));
    }

    record VenueResponse(UUID id, String name, String timezone) { }

    record AvailabilityResponse(
        UUID venueId,
        String timezone,
        LocalDate date,
        List<AvailabilityItemResponse> items
    ) { }

    record AvailabilityItemResponse(
        UUID slotInventoryId,
        UUID resourceId,
        String resourceName,
        Instant startsAt,
        Instant endsAt,
        int seatingCapacity,
        int capacity,
        int occupied,
        int available
    ) { }
}
