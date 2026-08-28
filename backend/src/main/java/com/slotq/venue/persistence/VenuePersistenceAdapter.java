package com.slotq.venue.persistence;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.application.VenueRepository;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;
import com.slotq.venue.domain.WeeklyOperatingHours;
import org.springframework.stereotype.Component;

@Component
class VenuePersistenceAdapter implements VenueRepository {

    private final VenueSpringDataRepository venueRepository;
    private final OperatingHoursSpringDataRepository hoursRepository;
    private final BookingPolicySpringDataRepository policyRepository;

    VenuePersistenceAdapter(
        VenueSpringDataRepository venueRepository,
        OperatingHoursSpringDataRepository hoursRepository,
        BookingPolicySpringDataRepository policyRepository
    ) {
        this.venueRepository = venueRepository;
        this.hoursRepository = hoursRepository;
        this.policyRepository = policyRepository;
    }

    @Override
    public void create(Venue venue) {
        venueRepository.saveAndFlush(toEntity(venue));
        hoursRepository.saveAll(toHoursEntities(venue));
        policyRepository.save(toPolicyEntity(venue.tenantId(), venue.id(), venue.currentPolicy()));
    }

    @Override
    public Optional<Venue> find(TenantId tenantId, VenueId venueId) {
        return venueRepository.findByTenantIdAndId(tenantId.value(), venueId.value())
            .map(this::toDomain);
    }

    @Override
    public Optional<Venue> findForUpdate(TenantId tenantId, VenueId venueId) {
        return venueRepository.findForUpdate(tenantId.value(), venueId.value())
            .map(this::toDomain);
    }

    @Override
    public void updateConfiguration(Venue venue) {
        VenueJpaEntity entity = venueRepository.findByTenantIdAndId(venue.tenantId().value(), venue.id().value())
            .orElseThrow();
        entity.update(venue.status(), venue.timezone().getId());
        venueRepository.save(entity);
        hoursRepository.deleteForVenue(venue.tenantId().value(), venue.id().value());
        hoursRepository.saveAll(toHoursEntities(venue));
    }

    @Override
    public void appendPolicy(TenantId tenantId, VenueId venueId, BookingPolicy policy) {
        policyRepository.save(toPolicyEntity(tenantId, venueId, policy));
    }

    private VenueJpaEntity toEntity(Venue venue) {
        return new VenueJpaEntity(
            venue.id().value(),
            venue.tenantId().value(),
            venue.status(),
            venue.timezone().getId()
        );
    }

    private List<OperatingHoursJpaEntity> toHoursEntities(Venue venue) {
        UUID tenantId = venue.tenantId().value();
        UUID venueId = venue.id().value();
        return venue.operatingHours().openDays().entrySet().stream()
            .map(entry -> new OperatingHoursJpaEntity(
                new OperatingHoursId(tenantId, venueId, (byte) entry.getKey().getValue()),
                entry.getValue().opensAt(),
                entry.getValue().closesAt()
            ))
            .toList();
    }

    private BookingPolicyJpaEntity toPolicyEntity(TenantId tenantId, VenueId venueId, BookingPolicy policy) {
        return new BookingPolicyJpaEntity(
            new BookingPolicyId(tenantId.value(), venueId.value(), policy.version()),
            policy
        );
    }

    private Venue toDomain(VenueJpaEntity entity) {
        EnumMap<java.time.DayOfWeek, DailyOperatingHours> openDays = new EnumMap<>(java.time.DayOfWeek.class);
        hoursRepository.findForVenue(entity.tenantId(), entity.id())
            .forEach(hours -> openDays.put(hours.dayOfWeek(), hours.toDomain()));
        BookingPolicy currentPolicy = policyRepository
            .findFirstByIdTenantIdAndIdVenueIdOrderByIdPolicyVersionDesc(entity.tenantId(), entity.id())
            .orElseThrow(() -> new IllegalStateException("Venue has no booking policy: " + entity.id()))
            .toDomain();
        return new Venue(
            new VenueId(entity.id()),
            new TenantId(entity.tenantId()),
            entity.status(),
            ZoneId.of(entity.timezone()),
            new WeeklyOperatingHours(openDays),
            currentPolicy
        );
    }
}
