package com.slotq.web;

import java.sql.SQLException;
import java.util.Arrays;

import com.slotq.auth.application.AccessDeniedException;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.booking.application.ProductApiException;
import com.slotq.booking.application.ProductError;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ProductApiErrorContractTests {

    private final ProductApiExceptionHandler handler = new ProductApiExceptionHandler();
    private final HttpServletRequest request = request();

    @Test
    void mapsEveryReservationConflictToStable409ProblemCode() {
        assertThat(Arrays.stream(ProductError.values()).map(Enum::name)).containsExactly(
            "CAPACITY_UNAVAILABLE",
            "PARTY_SIZE_NOT_SUPPORTED",
            "BOOKING_NOT_ALLOWED",
            "IDEMPOTENCY_KEY_REUSED",
            "HOLD_EXPIRED",
            "CANCELLATION_WINDOW_CLOSED",
            "RESERVATION_TRANSITION_NOT_ALLOWED"
        );

        for (ProductError error : ProductError.values()) {
            ApiProblem problem = handler.productConflict(exception(error), request).getBody();
            assertThat(problem).isNotNull();
            assertThat(problem.status()).isEqualTo(409);
            assertThat(problem.code()).isEqualTo(error.name());
            assertThat(problem.type()).isNotBlank();
            assertThat(problem.title()).isNotBlank();
            assertThat(problem.detail()).isNotBlank();
            assertThat(problem.instance()).isEqualTo("/api/v1/test");
            assertThat(problem.fieldErrors()).isNull();
        }
    }

    @Test
    void mapsHiddenNotFoundAccessDeniedAndInternalErrorWithoutSensitiveDetail() {
        ApiProblem notFound = handler.notFound(new ResourceNotFoundException(), request).getBody();
        ApiProblem denied = handler.accessDenied(new AccessDeniedException(), request).getBody();
        ApiProblem internal = handler.internalError(
            new RuntimeException("secret SQL tenant customer stack"), request
        ).getBody();

        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("ACCESS_DENIED");
        assertThat(internal.status()).isEqualTo(500);
        assertThat(internal.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(internal.detail()).doesNotContain("secret", "SQL", "tenant", "customer", "stack");
    }

    @Test
    void mapsConnectionAcquisitionFailureToInternalSystemFailure() {
        CannotGetJdbcConnectionException failure = new CannotGetJdbcConnectionException(
            "connection secret", new SQLException("database host secret")
        );

        var response = handler.internalError(failure, request);
        ApiProblem problem = response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(problem).isNotNull();
        assertThat(problem.status()).isEqualTo(500);
        assertThat(problem.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(problem.detail()).doesNotContain("connection", "database", "host", "secret");
    }

    private ProductApiException exception(ProductError error) {
        return new ProductApiException(error) { };
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/test");
        return request;
    }
}
