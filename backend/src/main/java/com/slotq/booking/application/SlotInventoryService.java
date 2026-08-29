package com.slotq.booking.application;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.regex.Pattern;

import com.slotq.booking.domain.SlotInventory;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.application.ResourceRepository;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class SlotInventoryService implements SlotInventoryUseCase {

    private static final Pattern RFC_3339_OFFSET_TIMESTAMP = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})$"
    );

    private final SlotInventoryRepository slotRepository;
    private final ResourceRepository resourceRepository;
    private final VenueConfigurationUseCase venueUseCase;

    SlotInventoryService(
        SlotInventoryRepository slotRepository,
        ResourceRepository resourceRepository,
        VenueConfigurationUseCase venueUseCase
    ) {
        this.slotRepository = slotRepository;
        this.resourceRepository = resourceRepository;
        this.venueUseCase = venueUseCase;
    }

    @Override
    public SlotInventory createSlot(CreateSlot command) {
        Objects.requireNonNull(command, "command must not be null");
        Venue venue = venueUseCase.getVenue(command.tenantId(), command.venueId());
        Resource resource = resourceRepository.findForUpdate(
            command.tenantId(), command.venueId(), command.resourceId()
        ).orElseThrow(() -> new NoSuchElementException("Resource not found in tenant and venue scope"));
        if (resource.status() != ResourceStatus.ACTIVE) {
            throw new IllegalStateException("Cannot create a Slot for an INACTIVE Resource");
        }

        OffsetDateTime input = parseOffsetTimestamp(command.startsAt());
        validateVenueOffset(input, venue);
        LocalDateTime localStart = input.toInstant().atZone(venue.timezone()).toLocalDateTime();
        int durationMinutes = venue.currentPolicy().terms().slotDurationMinutes();
        validateOperatingHoursAndGrid(localStart, durationMinutes, venue);

        SlotInventory slot = SlotInventory.create(
            SlotInventoryId.newId(),
            command.tenantId(),
            command.venueId(),
            resource,
            input.toInstant(),
            durationMinutes,
            venue.currentPolicy().version()
        );
        if (slotRepository.overlaps(
            command.tenantId(), command.venueId(), command.resourceId(), slot.startsAt(), slot.endsAt()
        )) {
            throw new IllegalArgumentException("Slot overlaps an existing Slot for this Resource");
        }
        slotRepository.save(slot);
        return slot;
    }

    @Override
    @Transactional(readOnly = true)
    public SlotInventory getSlot(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId
    ) {
        return slotRepository.find(tenantId, venueId, resourceId, slotInventoryId)
            .orElseThrow(() -> new NoSuchElementException("SlotInventory not found in tenant and venue scope"));
    }

    private OffsetDateTime parseOffsetTimestamp(String value) {
        String timestamp = Objects.requireNonNull(value, "startsAt must not be null");
        if (!RFC_3339_OFFSET_TIMESTAMP.matcher(timestamp).matches()) {
            throw new IllegalArgumentException("startsAt must be an offset RFC 3339 timestamp");
        }
        try {
            return OffsetDateTime.parse(
                timestamp,
                DateTimeFormatter.ISO_OFFSET_DATE_TIME
            );
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("startsAt must be an offset RFC 3339 timestamp", exception);
        }
    }

    private void validateVenueOffset(OffsetDateTime input, Venue venue) {
        LocalDateTime suppliedLocalTime = input.toLocalDateTime();
        List<ZoneOffset> validOffsets = venue.timezone().getRules().getValidOffsets(suppliedLocalTime);
        if (validOffsets.isEmpty()) {
            throw new DateTimeException("startsAt is in a DST gap for the Venue timezone");
        }
        if (!validOffsets.contains(input.getOffset())) {
            throw new DateTimeException("startsAt offset is not valid for the Venue local time");
        }
    }

    private void validateOperatingHoursAndGrid(LocalDateTime localStart, int durationMinutes, Venue venue) {
        DailyOperatingHours hours = venue.operatingHours().hoursOn(localStart.getDayOfWeek())
            .orElseThrow(() -> new IllegalArgumentException("Slot must start on an open Venue day"));
        LocalDateTime opensAt = localStart.toLocalDate().atTime(hours.opensAt());
        LocalDateTime closesAt = localStart.toLocalDate().atTime(hours.closesAt());
        LocalDateTime localEnd = localStart.plusMinutes(durationMinutes);
        if (localStart.isBefore(opensAt) || localEnd.isAfter(closesAt)) {
            throw new IllegalArgumentException("Slot must fit within Venue operating hours");
        }
        long minutesFromOpen = Duration.between(opensAt, localStart).toMinutes();
        if (!opensAt.plusMinutes(minutesFromOpen).equals(localStart)
            || Math.floorMod(minutesFromOpen, durationMinutes) != 0) {
            throw new IllegalArgumentException("Slot must align with the Venue slot grid");
        }
    }
}
