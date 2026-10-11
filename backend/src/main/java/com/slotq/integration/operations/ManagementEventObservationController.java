package com.slotq.integration.operations;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.venue.domain.VenueId;
import com.slotq.web.ApiProblem;
import com.slotq.observability.web.RequestOutcome;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/management/venues/{venueId}/event-observations")
class ManagementEventObservationController {
    private final OperationsObservationReadService observations;

    ManagementEventObservationController(OperationsObservationReadService observations) {
        this.observations = observations;
    }

    @GetMapping
    ResponseEntity<OperationsObservationReadService.Page> list(
        @PathVariable UUID venueId, @RequestParam Instant from, @RequestParam Instant to,
        @RequestParam(required = false) Integer limit,
        @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        var page = observations.list(new VenueId(venueId), from, to, limit, principal);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    // The global fallback treats arbitrary exceptions as 500; these are the observation query's 400 validations.
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiProblem> invalidQuery(ResponseStatusException exception, HttpServletRequest request) {
        var problem = ApiProblem.validation("Observation query is invalid.", request.getRequestURI(), Map.of());
        RequestOutcome.problem(request, problem.code());
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
