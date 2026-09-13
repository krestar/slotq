package com.slotq.waitlist.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.slotq.auth.application.AccessDeniedException;
import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.ActorContext;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.booking.application.WaitlistDemandQuery;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.application.WaitlistVenueQuery;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistEntry;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistEntryState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WaitlistService implements WaitlistUseCase {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final WaitlistCommandExecutor commandExecutor;
    private final WaitlistEntryRepository entryRepository;
    private final WaitlistRegistrationStore registrationStore;
    private final WaitlistVenueQuery venueQuery;
    private final WaitlistDemandQuery demandQuery;
    private final AuthorizationUseCase authorization;
    private final Clock clock;

    WaitlistService(
        WaitlistCommandExecutor commandExecutor,
        WaitlistEntryRepository entryRepository,
        WaitlistRegistrationStore registrationStore,
        WaitlistVenueQuery venueQuery,
        WaitlistDemandQuery demandQuery,
        AuthorizationUseCase authorization,
        Clock clock
    ) {
        this.commandExecutor = commandExecutor;
        this.entryRepository = entryRepository;
        this.registrationStore = registrationStore;
        this.venueQuery = venueQuery;
        this.demandQuery = demandQuery;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Override
    public Registration register(CreateRegistration command) {
        Instant commandNow = clock.instant();
        WaitlistCommandExecutor.Registration result = commandExecutor.register(
            command.venueId(), command.slotInventoryId(), command.principal().principalId(),
            command.partySize(), command.key(), commandNow
        );
        WaitlistVenueQuery.VenueScope venue = requireVenue(command.venueId());
        Instant observedAt = clock.instant();
        return new Registration(
            view(result.entry(), observedAt, venue.timezone(), true), result.originalStatus()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public RegistrationReceipt getRegistration(
        VenueId venueId,
        WaitlistRegistrationKey key,
        AuthenticatedPrincipal principal
    ) {
        WaitlistRegistrationStore.Result result = registrationStore.find(
            venueId, principal.principalId(), key
        ).orElseThrow(ResourceNotFoundException::new);
        return new RegistrationReceipt(
            result.entryId().value(), entryLocation(venueId, result.entryId()), result.originalStatus()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public EntryView getEntry(
        VenueId venueId,
        WaitlistEntryId entryId,
        AuthenticatedPrincipal principal
    ) {
        Instant observedAt = clock.instant();
        WaitlistEntry entry = entryRepository.findOwned(
            venueId, principal.principalId(), entryId
        ).orElseThrow(ResourceNotFoundException::new);
        return view(entry, observedAt, requireVenue(venueId).timezone(), true);
    }

    @Override
    @Transactional(readOnly = true)
    public EntryPage getEntries(
        VenueId venueId,
        LocalDate date,
        String cursor,
        Integer requestedLimit,
        AuthenticatedPrincipal principal
    ) {
        int limit = validatedLimit(requestedLimit);
        WaitlistVenueQuery.VenueScope venue = requireVenue(venueId);
        Instant observedAt = clock.instant();
        DateWindow window = window(date, venue.timezone());
        String cursorScope = customerScope(venueId, principal.principalId(), date);
        Optional<WaitlistEntryRepository.Seek> seek = decodeCursor(cursor, cursorScope);
        List<WaitlistEntry> fetched = entryRepository.findAllOwned(
            venue.tenantId(), venueId, principal.principalId(), window.startsAt(), window.endsAt(),
            seek, limit + 1
        ).entries();
        return new EntryPage(
            pageItems(fetched, limit).stream()
                .map(entry -> view(entry, observedAt, venue.timezone(), true)).toList(),
            nextCursor(fetched, limit, cursorScope), observedAt, venue.timezone()
        );
    }

    @Override
    public EntryView cancel(
        VenueId venueId,
        WaitlistEntryId entryId,
        AuthenticatedPrincipal principal
    ) {
        WaitlistEntry routed = entryRepository.findOwned(
            venueId, principal.principalId(), entryId
        ).orElseThrow(ResourceNotFoundException::new);
        Instant commandNow = clock.instant();
        WaitlistEntry cancelled = commandExecutor.cancel(
            venueId, principal.principalId(), routed, commandNow
        );
        WaitlistVenueQuery.VenueScope venue = requireVenue(venueId);
        return view(cancelled, clock.instant(), venue.timezone(), true);
    }

    @Override
    @Transactional(readOnly = true)
    public ManagementPage getManagementEntries(
        VenueId venueId,
        LocalDate date,
        SlotInventoryId slotInventoryId,
        String cursor,
        Integer requestedLimit,
        AuthenticatedPrincipal principal
    ) {
        int limit = validatedLimit(requestedLimit);
        ActorContext actor = authorization.requireVenueAccess(principal, venueId);
        if (actor.role() != TenantRole.OWNER && actor.role() != TenantRole.MANAGER) {
            throw new AccessDeniedException();
        }
        WaitlistVenueQuery.VenueScope venue = requireVenue(venueId);
        if (!actor.tenantId().equals(venue.tenantId())) {
            throw new ResourceNotFoundException();
        }
        WaitlistDemandQuery.SlotTarget slot = slotInventoryId == null ? null
            : demandQuery.findSlot(venueId, slotInventoryId)
                .filter(found -> found.tenantId().equals(actor.tenantId()))
                .orElseThrow(ResourceNotFoundException::new);
        DateWindow window = window(date, venue.timezone());
        String cursorScope = managementScope(venueId, date, slotInventoryId);
        Optional<WaitlistEntryRepository.Seek> seek = decodeCursor(cursor, cursorScope);
        Optional<WaitlistEntryRepository.TimeWindow> demandWindow = slot == null
            ? Optional.empty()
            : Optional.of(new WaitlistEntryRepository.TimeWindow(slot.startsAt(), slot.endsAt()));
        List<WaitlistEntry> fetched = entryRepository.findAllForVenue(
            actor.tenantId(), venueId, window.startsAt(), window.endsAt(), demandWindow,
            seek, limit + 1
        ).entries();
        Instant observedAt = clock.instant();
        List<ManagementItem> items = pageItems(fetched, limit).stream().map(entry ->
            new ManagementItem(
                view(entry, observedAt, venue.timezone(), false),
                slot == null ? null : slot.eligible(entry.demand().partySize())
            )
        ).toList();
        return new ManagementPage(
            items, nextCursor(fetched, limit, cursorScope), observedAt, venue.timezone()
        );
    }

    private WaitlistVenueQuery.VenueScope requireVenue(VenueId venueId) {
        return venueQuery.find(venueId).orElseThrow(ResourceNotFoundException::new);
    }

    private EntryView view(
        WaitlistEntry entry,
        Instant observedAt,
        ZoneId timezone,
        boolean customer
    ) {
        WaitlistEntryState state = entry.effectiveState(observedAt);
        List<String> actions = customer && state == WaitlistEntryState.WAITING
            ? List.of("CANCEL") : List.of();
        return new EntryView(
            entry.id().value(), entry.venueId().value(), entry.demand().startsAt(),
            entry.demand().endsAt(), entry.demand().partySize(), entry.joinedAt(), state,
            observedAt, timezone, actions, null
        );
    }

    private int validatedLimit(Integer requested) {
        int limit = requested == null ? DEFAULT_LIMIT : requested;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new WaitlistValidationException(Map.of(
                "limit", "limit must be between 1 and 100."
            ));
        }
        return limit;
    }

    private DateWindow window(LocalDate date, ZoneId timezone) {
        if (date == null) {
            throw new WaitlistValidationException(Map.of("date", "date is required."));
        }
        return new DateWindow(
            date.atStartOfDay(timezone).toInstant(),
            date.plusDays(1).atStartOfDay(timezone).toInstant()
        );
    }

    private List<WaitlistEntry> pageItems(List<WaitlistEntry> fetched, int limit) {
        return fetched.size() <= limit ? fetched : fetched.subList(0, limit);
    }

    private String nextCursor(List<WaitlistEntry> fetched, int limit, String scope) {
        if (fetched.size() <= limit) {
            return null;
        }
        WaitlistEntry last = fetched.get(limit - 1);
        String plain = scope + "|" + last.joinedAt() + "|" + last.id().value();
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private Optional<WaitlistEntryRepository.Seek> decodeCursor(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String prefix = scope + "|";
            if (!plain.startsWith(prefix)) {
                throw new IllegalArgumentException("cursor scope mismatch");
            }
            String remainder = plain.substring(prefix.length());
            int separator = remainder.lastIndexOf('|');
            if (separator <= 0 || separator == remainder.length() - 1) {
                throw new IllegalArgumentException("cursor shape invalid");
            }
            return Optional.of(new WaitlistEntryRepository.Seek(
                Instant.parse(remainder.substring(0, separator)),
                new WaitlistEntryId(java.util.UUID.fromString(remainder.substring(separator + 1)))
            ));
        } catch (RuntimeException invalid) {
            throw new WaitlistValidationException(Map.of("cursor", "cursor is invalid."));
        }
    }

    private String customerScope(VenueId venueId, PrincipalId principalId, LocalDate date) {
        return "customer|" + venueId.value() + "|" + principalId.value() + "|" + date;
    }

    private String managementScope(
        VenueId venueId,
        LocalDate date,
        SlotInventoryId slotInventoryId
    ) {
        return "management|" + venueId.value() + "|" + date + "|"
            + (slotInventoryId == null ? "-" : slotInventoryId.value());
    }

    private String entryLocation(VenueId venueId, WaitlistEntryId entryId) {
        return "/api/v1/venues/" + venueId.value() + "/waitlist-entries/" + entryId.value();
    }

    private record DateWindow(Instant startsAt, Instant endsAt) { }
}
