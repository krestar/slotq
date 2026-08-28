package com.slotq.auth.dev;

import java.util.Map;
import java.util.UUID;

import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.application.AccessDeniedException;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.venue.domain.VenueId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local | test")
@ConditionalOnProperty(name = "slotq.auth.dev-bootstrap-enabled", havingValue = "true")
class DevAuthSessionController {

    private final DevCredentialStore credentialStore;
    private final AuthorizationUseCase authorizationUseCase;

    DevAuthSessionController(
        DevCredentialStore credentialStore,
        AuthorizationUseCase authorizationUseCase
    ) {
        this.credentialStore = credentialStore;
        this.authorizationUseCase = authorizationUseCase;
    }

    @GetMapping("/__dev/auth/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifySession(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        if (principal == null) {
            throw new IllegalStateException("Authenticated principal is required");
        }
    }

    @GetMapping("/__dev/auth/session/venues/{venueId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyVenueScope(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @PathVariable UUID venueId
    ) {
        authorizationUseCase.requireVenueAccess(principal, new VenueId(venueId));
    }

    @PostMapping("/__dev/auth/session")
    ResponseEntity<SessionResponse> createSession(@RequestBody Map<String, Object> request) {
        String fixtureKey = validate(request);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new SessionResponse(credentialStore.credentialFor(fixtureKey), "Bearer"));
    }

    @ExceptionHandler({UnknownFixtureException.class, InvalidBootstrapRequestException.class})
    ResponseEntity<Map<String, String>> validationFailure() {
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void hiddenNotFound() {
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    void forbidden() {
    }

    private String validate(Map<String, Object> request) {
        if (request.size() != 1 || !request.containsKey("fixtureKey")) {
            throw new InvalidBootstrapRequestException();
        }
        Object fixtureKey = request.get("fixtureKey");
        if (!(fixtureKey instanceof String value) || value.isBlank()) {
            throw new InvalidBootstrapRequestException();
        }
        return value;
    }

    record SessionResponse(String accessToken, String tokenType) {
    }

    private static final class InvalidBootstrapRequestException extends RuntimeException {
    }
}
