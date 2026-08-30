package com.slotq.architecture;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.slotq.auth.application.AuthorizationUseCase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextDependencyArchitectureTests {

    @Test
    void accessAndTenancyDoesNotDependOnBooking() throws IOException, URISyntaxException {
        Path mainClasses = Path.of(
            AuthorizationUseCase.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        List<String> violations = new ArrayList<>();

        for (String contextPackage : List.of("auth", "tenancy")) {
            Path contextClasses = mainClasses.resolve("com/slotq/" + contextPackage);
            try (var paths = Files.walk(contextClasses)) {
                for (Path path : paths.filter(value -> value.toString().endsWith(".class")).toList()) {
                    String classFile = new String(
                        Files.readAllBytes(path), StandardCharsets.ISO_8859_1
                    );
                    if (classFile.contains("com/slotq/booking/")) {
                        violations.add(contextPackage + "/" + contextClasses.relativize(path));
                    }
                }
            }
        }

        assertThat(violations)
            .as("Access and Tenancy must not depend on Booking")
            .isEmpty();
    }
}
