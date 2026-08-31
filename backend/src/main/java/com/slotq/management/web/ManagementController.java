package com.slotq.management.web;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.slotq.auth.application.ReservationAction;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.management.application.ManagementUseCase;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;
import com.slotq.venue.domain.VenueStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/management")
class ManagementController {

    private final ManagementUseCase management;

    ManagementController(ManagementUseCase management) {
        this.management = management;
    }

    @GetMapping("/venues")
    ResponseEntity<List<VenueResponse>> venues(
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return noStore(management.getVenues(principal).stream().map(this::venue).toList());
    }

    @GetMapping("/venues/{venueId}")
    ResponseEntity<VenueResponse> venue(
        @PathVariable UUID venueId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return noStore(venue(management.getVenue(principal, new VenueId(venueId))));
    }

    @PatchMapping("/venues/{venueId}")
    ResponseEntity<VenueResponse> patchVenue(
        @PathVariable UUID venueId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @RequestBody VenuePatchRequest request
    ) {
        ManagementUseCase.PatchVenue patch = request == null ? null
            : new ManagementUseCase.PatchVenue(request.name(), request.status());
        return ResponseEntity.ok(venue(management.patchVenue(
            principal, new VenueId(venueId), patch
        )));
    }

    @GetMapping("/venues/{venueId}/policy")
    ResponseEntity<PolicyResponse> policy(
        @PathVariable UUID venueId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return noStore(policy(management.getPolicy(principal, new VenueId(venueId))));
    }

    @PutMapping("/venues/{venueId}/policy")
    ResponseEntity<PolicyResponse> updatePolicy(
        @PathVariable UUID venueId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @Valid @RequestBody PolicyRequest request
    ) {
        BookingPolicy updated = management.updatePolicy(
            principal,
            new VenueId(venueId),
            request.terms()
        );
        return ResponseEntity.ok(policy(updated));
    }

    @GetMapping("/venues/{venueId}/resources")
    ResponseEntity<List<ResourceResponse>> resources(
        @PathVariable UUID venueId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return noStore(management.getResources(principal, new VenueId(venueId)).stream()
            .map(this::resource).toList());
    }

    @PostMapping("/venues/{venueId}/resources")
    ResponseEntity<ResourceResponse> createResource(
        @PathVariable UUID venueId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @Valid @RequestBody ResourceRequest request
    ) {
        Resource created = management.createResource(
            principal,
            new VenueId(venueId),
            new ManagementUseCase.CreateResource(request.name(), request.seatingCapacity())
        );
        return ResponseEntity.created(URI.create(
            "/api/v1/management/venues/" + venueId + "/resources/" + created.id().value()
        )).body(resource(created));
    }

    @PatchMapping("/venues/{venueId}/resources/{resourceId}")
    ResponseEntity<ResourceResponse> patchResource(
        @PathVariable UUID venueId,
        @PathVariable UUID resourceId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @RequestBody ResourcePatchRequest request
    ) {
        ManagementUseCase.PatchResource patch = request == null ? null
            : new ManagementUseCase.PatchResource(
                request.name(), request.seatingCapacity(), request.status()
            );
        Resource updated = management.patchResource(
            principal, new VenueId(venueId), new ResourceId(resourceId), patch
        );
        return ResponseEntity.ok(resource(updated));
    }

    @GetMapping("/venues/{venueId}/slot-inventories")
    ResponseEntity<List<SlotInventoryResponse>> slotInventories(
        @PathVariable UUID venueId,
        @RequestParam LocalDate date,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return noStore(management.getSlotInventories(
            principal, new VenueId(venueId), date
        ).stream().map(this::slot).toList());
    }

    @PostMapping("/venues/{venueId}/slot-inventories")
    ResponseEntity<SlotInventoryResponse> createSlotInventory(
        @PathVariable UUID venueId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @Valid @RequestBody SlotInventoryRequest request
    ) {
        SlotInventory created = management.createSlotInventory(
            principal,
            new VenueId(venueId),
            new ManagementUseCase.CreateSlotInventory(
                new ResourceId(request.resourceId()), request.startsAt()
            )
        );
        return ResponseEntity.created(URI.create(
            "/api/v1/management/venues/" + venueId + "/slot-inventories/" + created.id().value()
        )).body(slot(created));
    }

    @GetMapping("/venues/{venueId}/reservations")
    ResponseEntity<List<ReservationResponse>> reservations(
        @PathVariable UUID venueId,
        @RequestParam LocalDate date,
        @RequestParam(required = false) ReservationState status,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return noStore(management.getReservations(
            principal, new VenueId(venueId), date, status
        ).stream().map(this::reservation).toList());
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private VenueResponse venue(ManagementUseCase.VenueItem item) {
        Venue venue = item.venue();
        return new VenueResponse(
            venue.id().value(), venue.name(), venue.timezone().getId(), venue.status(),
            venue.currentPolicy().version(), item.configurationWritable()
        );
    }

    private PolicyResponse policy(BookingPolicy policy) {
        BookingPolicyTerms terms = policy.terms();
        return new PolicyResponse(
            policy.version(), terms.slotDurationMinutes(), terms.holdDurationMinutes(),
            terms.cancellationCutoffMinutes(), terms.noShowGraceMinutes(), policy.createdAt()
        );
    }

    private ResourceResponse resource(Resource resource) {
        return new ResourceResponse(
            resource.id().value(), resource.type().name(), resource.name(),
            resource.seatingCapacity(), resource.status()
        );
    }

    private SlotInventoryResponse slot(SlotInventory slot) {
        return new SlotInventoryResponse(
            slot.id().value(), slot.resourceId().value(), slot.startsAt(), slot.endsAt(),
            slot.capacity(), slot.appliedPolicyVersion()
        );
    }

    private ReservationResponse reservation(ManagementUseCase.ReservationItem item) {
        Reservation reservation = item.reservation();
        return new ReservationResponse(
            reservation.id().value(), reservation.resourceId().value(),
            reservation.slotInventoryId().value(), item.effectiveState(),
            reservation.partySize().value(), reservation.startsAt(), item.endsAt(),
            reservation.expiresAt(), reservation.customerPrincipalId().value(),
            item.allowedActions().stream().map(this::action).toList()
        );
    }

    private String action(ReservationAction action) {
        return switch (action) {
            case CANCEL -> "cancel";
            case CHECK_IN -> "check-in";
            case NO_SHOW -> "no-show";
            case COMPLETE -> "complete";
            case CONFIRM -> throw new IllegalArgumentException("confirm is not a management action");
        };
    }

    record VenueResponse(
        UUID id,
        String name,
        String timezone,
        VenueStatus status,
        long currentPolicyVersion,
        boolean configurationWritable
    ) { }

    record PolicyResponse(
        long version,
        int slotDurationMinutes,
        int holdDurationMinutes,
        int cancellationCutoffMinutes,
        int noShowGraceMinutes,
        Instant createdAt
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record PolicyRequest(
        @NotNull @Positive Integer slotDurationMinutes,
        @NotNull @Positive Integer holdDurationMinutes,
        @NotNull @PositiveOrZero Integer cancellationCutoffMinutes,
        @NotNull @PositiveOrZero Integer noShowGraceMinutes
    ) {
        BookingPolicyTerms terms() {
            return new BookingPolicyTerms(
                slotDurationMinutes, holdDurationMinutes,
                cancellationCutoffMinutes, noShowGraceMinutes
            );
        }
    }

    record ResourceResponse(
        UUID id,
        String type,
        String name,
        int seatingCapacity,
        ResourceStatus status
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record ResourceRequest(
        @NotBlank @Size(max = 100) String name,
        @Min(1) int seatingCapacity
    ) { }

    record SlotInventoryResponse(
        UUID id,
        UUID resourceId,
        Instant startsAt,
        Instant endsAt,
        int capacity,
        long appliedPolicyVersion
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record SlotInventoryRequest(
        @NotNull UUID resourceId,
        @NotBlank String startsAt
    ) { }

    record ReservationResponse(
        UUID id,
        UUID resourceId,
        UUID slotInventoryId,
        ReservationState state,
        int partySize,
        Instant startsAt,
        Instant endsAt,
        Instant expiresAt,
        UUID customerReference,
        List<String> allowedActions
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    static final class VenuePatchRequest {
        private ManagementUseCase.PatchField<String> name = new ManagementUseCase.PatchField<>(false, null);
        private ManagementUseCase.PatchField<VenueStatus> status = new ManagementUseCase.PatchField<>(false, null);

        @JsonSetter("name")
        void setName(String value) {
            name = new ManagementUseCase.PatchField<>(true, value);
        }

        @JsonSetter("status")
        void setStatus(VenueStatus value) {
            status = new ManagementUseCase.PatchField<>(true, value);
        }

        ManagementUseCase.PatchField<String> name() { return name; }
        ManagementUseCase.PatchField<VenueStatus> status() { return status; }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    static final class ResourcePatchRequest {
        private ManagementUseCase.PatchField<String> name = new ManagementUseCase.PatchField<>(false, null);
        private ManagementUseCase.PatchField<Integer> seatingCapacity =
            new ManagementUseCase.PatchField<>(false, null);
        private ManagementUseCase.PatchField<ResourceStatus> status =
            new ManagementUseCase.PatchField<>(false, null);

        @JsonSetter("name")
        void setName(String value) {
            name = new ManagementUseCase.PatchField<>(true, value);
        }

        @JsonSetter("seatingCapacity")
        void setSeatingCapacity(Integer value) {
            seatingCapacity = new ManagementUseCase.PatchField<>(true, value);
        }

        @JsonSetter("status")
        void setStatus(ResourceStatus value) {
            status = new ManagementUseCase.PatchField<>(true, value);
        }

        ManagementUseCase.PatchField<String> name() { return name; }
        ManagementUseCase.PatchField<Integer> seatingCapacity() { return seatingCapacity; }
        ManagementUseCase.PatchField<ResourceStatus> status() { return status; }
    }
}
