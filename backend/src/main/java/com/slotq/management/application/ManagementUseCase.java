package com.slotq.management.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.slotq.auth.application.ReservationAction;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;
import com.slotq.venue.domain.VenueStatus;

public interface ManagementUseCase {

    List<VenueItem> getVenues(AuthenticatedPrincipal principal);

    VenueItem getVenue(AuthenticatedPrincipal principal, VenueId venueId);

    VenueItem patchVenue(AuthenticatedPrincipal principal, VenueId venueId, PatchVenue patch);

    BookingPolicy getPolicy(AuthenticatedPrincipal principal, VenueId venueId);

    BookingPolicy updatePolicy(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        BookingPolicyTerms terms
    );

    List<Resource> getResources(AuthenticatedPrincipal principal, VenueId venueId);

    Resource createResource(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        CreateResource command
    );

    Resource patchResource(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        ResourceId resourceId,
        PatchResource patch
    );

    List<SlotInventory> getSlotInventories(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        LocalDate date
    );

    SlotInventory createSlotInventory(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        CreateSlotInventory command
    );

    List<ReservationItem> getReservations(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        LocalDate date,
        ReservationState status
    );

    record PatchField<T>(boolean supplied, T value) { }

    record PatchVenue(PatchField<String> name, PatchField<VenueStatus> status) { }

    record CreateResource(String name, int seatingCapacity) { }

    record PatchResource(
        PatchField<String> name,
        PatchField<Integer> seatingCapacity,
        PatchField<ResourceStatus> status
    ) { }

    record CreateSlotInventory(ResourceId resourceId, String startsAt) { }

    record VenueItem(Venue venue, boolean configurationWritable) { }

    record ReservationItem(
        Reservation reservation,
        Instant endsAt,
        ReservationState effectiveState,
        List<ReservationAction> allowedActions
    ) {
        public ReservationItem {
            allowedActions = List.copyOf(allowedActions);
        }
    }
}
