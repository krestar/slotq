package com.slotq.integration.waitlist;

import java.util.Set;
import java.util.UUID;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.PromotionReferenceException;
import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryFailure;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventHandlingException;
import com.slotq.events.application.StoredEvent;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.PromotionIdentityException;
import com.slotq.waitlist.application.WaitlistPromotionUseCase;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Integration owns event vocabulary; neither business context depends on delivery internals. */
@Component
class WaitlistPromotionEventAdapter {
    private final WaitlistPromotionUseCase promotion;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    WaitlistPromotionEventAdapter(WaitlistPromotionUseCase promotion) { this.promotion = promotion; }

    void handle(StoredEvent stored, ConsumerRoute route, WaitlistPromotionUseCase.Signal signal) {
        var command = decode(stored.envelope(), route, signal);
        try {
            promotion.promote(SystemPrincipal.INSTANCE, command);
        } catch (PromotionIdentityException conflict) {
            throw new EventHandlingException(DeliveryFailure.IDENTITY_CORRUPTION);
        } catch (PromotionReferenceException invalid) {
            throw new EventHandlingException(invalid.reason() == PromotionReferenceException.Reason.TENANT_MISMATCH
                ? DeliveryFailure.TENANT_MISMATCH : DeliveryFailure.PAYLOAD_INVALID);
        }
        // Infrastructure failures pass through to the existing M3 classifier; never return a no-op.
    }

    WaitlistPromotionUseCase.Command decode(EventEnvelope event, ConsumerRoute route,
                                                     WaitlistPromotionUseCase.Signal signal) {
        if (event.schemaVersion() != route.schemaVersion()) throw new EventHandlingException(DeliveryFailure.UNSUPPORTED_VERSION);
        if (!event.eventType().equals(route.eventType())) throw new EventHandlingException(DeliveryFailure.TARGET_ROUTE_CORRUPTION);
        boolean release = signal == WaitlistPromotionUseCase.Signal.CAPACITY_RELEASED;
        if (!event.aggregateType().equals(release ? "Reservation" : "SlotInventory")) {
            throw new EventHandlingException(DeliveryFailure.PAYLOAD_INVALID);
        }
        try {
            JsonNode payload = json.readTree(event.payload());
            Set<String> fields = release
                ? Set.of("venueId", "resourceId", "slotInventoryId", "fromState", "toState")
                : Set.of("venueId", "resourceId", "slotInventoryId");
            if (payload == null || !payload.isObject() || !Set.copyOf(payload.propertyNames()).equals(fields)) {
                throw new IllegalArgumentException("Unexpected payload fields");
            }
            UUID slotId = id(payload, "slotInventoryId");
            ReservationState from = release ? ReservationState.valueOf(string(payload, "fromState")) : null;
            ReservationState to = release ? ReservationState.valueOf(string(payload, "toState")) : null;
            if (release && !releasePair(from, to)) throw new IllegalArgumentException("Not a capacity release transition");
            if (!release && !event.aggregateId().equals(slotId)) throw new EventHandlingException(DeliveryFailure.IDENTITY_CORRUPTION);
            return new WaitlistPromotionUseCase.Command(event.tenantId(), event.eventId().value(), signal,
                event.aggregateId(), event.occurredAt(), new VenueId(id(payload, "venueId")),
                new ResourceId(id(payload, "resourceId")), new SlotInventoryId(slotId), from, to);
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
            throw new EventHandlingException(DeliveryFailure.PAYLOAD_INVALID);
        }
    }

    private boolean releasePair(ReservationState from, ReservationState to) {
        return switch (from) {
            case HELD -> to == ReservationState.CANCELLED || to == ReservationState.EXPIRED;
            case CONFIRMED -> to == ReservationState.CANCELLED || to == ReservationState.NO_SHOW;
            case CHECKED_IN -> to == ReservationState.COMPLETED;
            default -> false;
        };
    }
    private UUID id(JsonNode payload, String name) {
        String value = string(payload, name);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
        return id;
    }
    private String string(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || !value.isString()) throw new IllegalArgumentException("Payload field must be a string");
        return value.asString();
    }
}
