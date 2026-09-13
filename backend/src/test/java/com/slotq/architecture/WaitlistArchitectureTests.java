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
import com.slotq.waitlist.domain.WaitlistEntryState;
import org.junit.jupiter.api.Test;

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
    void waitlistDoesNotReachIntoBookingPersistenceOrFutureOfferAndEventWork() throws Exception {
        List<String> forbiddenReferences = List.of(
            "com/slotq/booking/persistence/",
            "com/slotq/booking/domain/Reservation",
            "com/slotq/booking/domain/CapacityAllocation",
            "com/slotq/events/",
            "WaitlistOffer",
            "PromotionalHold"
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

    private List<Path> waitlistClasses() throws IOException, URISyntaxException {
        try (var paths = Files.walk(mainClasses().resolve("com/slotq/waitlist"))) {
            return paths.filter(path -> path.toString().endsWith(".class")).toList();
        }
    }

    private Path mainClasses() throws URISyntaxException {
        return Path.of(WaitlistUseCase.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
