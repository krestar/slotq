package com.slotq.architecture;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.slotq.waitlist.application.WaitlistRegistrationStore;
import com.slotq.waitlist.application.WaitlistUseCase;
import com.slotq.waitlist.application.WaitlistPromotionUseCase;
import com.slotq.waitlist.application.WaitlistPromotionReceiptStore;
import com.slotq.waitlist.application.WaitlistNotificationStore;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.events.application.StoredEvent;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.integration.waitlist.BookingCapacityReleasedHandler;
import com.slotq.integration.waitlist.WaitlistPromotionRequestedHandler;
import com.slotq.waitlist.domain.WaitlistEntryState;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import static org.assertj.core.api.Assertions.assertThat;

class WaitlistArchitectureTests {

    @Test
    void entryStatesAndRegistrationKeyKeepTheIssue94Contract() {
        assertThat(WaitlistEntryState.values()).containsExactly(
            WaitlistEntryState.WAITING,
            WaitlistEntryState.OFFERED,
            WaitlistEntryState.FULFILLED,
            WaitlistEntryState.DECLINED,
            WaitlistEntryState.EXPIRED,
            WaitlistEntryState.CANCELLED
        );
        assertThat(WaitlistRegistrationKey.class.getRecordComponents())
            .extracting(component -> component.getType().getName())
            .containsExactly(UUID.class.getName());
        assertThat(Arrays.stream(WaitlistRegistrationStore.class.getDeclaredMethods())
            .map(method -> method.getName()).toList())
            .containsExactlyInAnyOrder("claim", "complete", "find")
            .doesNotContain("delete", "cleanup", "expire");
    }

    @Test
    void waitlistUsesOnlyTheBookingPublicBoundaryAndDoesNotReachIntoPersistence() throws Exception {
        List<String> forbiddenReferences = List.of(
            "com/slotq/booking/persistence/",
            "com/slotq/booking/domain/Reservation;",
            "com/slotq/booking/domain/CapacityAllocation;",
            "com/slotq/events/"
        );
        List<String> violations = new ArrayList<>();
        for (Path classFile : waitlistClasses()) {
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            for (String forbidden : forbiddenReferences) {
                if (bytes.contains(forbidden)) {
                    violations.add(classFile.getFileName() + " -> " + forbidden);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void bookingDoesNotDependOnWaitlistOwnedTypes() throws Exception {
        List<String> violations = new ArrayList<>();
        Path classes = mainClasses();
        try (var paths = Files.walk(classes.resolve("com/slotq/booking"))) {
            for (Path classFile : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
                String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                if (bytes.contains("com/slotq/waitlist/")) {
                    violations.add(classes.relativize(classFile).toString());
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void twoExactProductionRoutesDelegateToOneMandatoryEventNeutralPromotionPort() throws Exception {
        assertThat(BookingCapacityReleasedHandler.ROUTE).isEqualTo(
            new ConsumerRoute("waitlist.promotion", "booking.capacity-released", 1));
        assertThat(WaitlistPromotionRequestedHandler.ROUTE).isEqualTo(
            new ConsumerRoute("waitlist.promotion", "waitlist.promotion-requested", 1));
        for (Class<?> handler : List.of(BookingCapacityReleasedHandler.class, WaitlistPromotionRequestedHandler.class)) {
            assertThat(handler.getMethod("handle", StoredEvent.class).getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
        }
        Class<?> promotion = Class.forName("com.slotq.waitlist.application.WaitlistPromotionService");
        assertThat(promotion.getMethod("promote", SystemPrincipal.class, WaitlistPromotionUseCase.Command.class)
            .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(WaitlistPromotionUseCase.Outcome.values()).extracting(Enum::name).containsExactly(
            "PROMOTED", "NO_CAPACITY", "NO_CANDIDATE", "NOT_ELIGIBLE", "SLOT_PAST", "DEFERRED");
        assertThat(Arrays.stream(WaitlistPromotionReceiptStore.class.getDeclaredMethods()).map(method -> method.getName()))
            .containsExactlyInAnyOrder("claim", "complete");
        assertThat(Arrays.stream(WaitlistNotificationStore.class.getDeclaredMethods()).map(method -> method.getName()))
            .containsExactly("offerAvailable");
    }

    @Test
    void promotionIntegrationCannotBootstrapOrDirectlyAccessBusinessPersistence() throws Exception {
        List<String> forbidden = List.of("/persistence/", "EventRegistrationService", "EventDeliveryWorker",
            "CapacityReleaseReadiness", "ApplicationRunner", "Scheduled");
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(mainClasses().resolve("com/slotq/integration/waitlist"))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".class")).toList()) {
                String bytes = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                forbidden.stream().filter(bytes::contains).forEach(reference -> violations.add(path.getFileName() + " -> " + reference));
            }
        }
        assertThat(violations).isEmpty();
    }

    private List<Path> waitlistClasses() throws IOException, URISyntaxException {
        try (var paths = Files.walk(mainClasses().resolve("com/slotq/waitlist"))) {
            return paths.filter(path -> path.toString().endsWith(".class")).toList();
        }
    }

    private Path mainClasses() throws URISyntaxException {
        return Path.of(WaitlistUseCase.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
