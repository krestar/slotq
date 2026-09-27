package com.slotq.observability.web;

import java.sql.SQLException;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

/** Bounded diagnostic codes only: never exception messages or request-supplied data. */
public final class RequestOutcome {
    private static final String ATTRIBUTE = RequestOutcome.class.getName();
    private static final Set<String> CODES = Set.of(
        "VALIDATION_FAILED", "WAITLIST_DEMAND_NOT_ALLOWED", "WAITLIST_TRANSITION_NOT_ALLOWED",
        "IDEMPOTENCY_KEY_REUSED", "OFFER_EXPIRED", "OFFER_TRANSITION_NOT_ALLOWED",
        "SLOT_INVENTORY_CONFLICT", "SLOT_INVENTORY_NOT_ALLOWED", "RESOURCE_NOT_FOUND",
        "ACCESS_DENIED", "AUTHENTICATION_REQUIRED", "INTERNAL_ERROR", "DB_DEADLOCK",
        "DB_LOCK_TIMEOUT", "DB_CONNECTION", "CAPACITY_UNAVAILABLE", "PARTY_SIZE_NOT_SUPPORTED",
        "BOOKING_NOT_ALLOWED", "HOLD_EXPIRED", "CANCELLATION_WINDOW_CLOSED",
        "RESERVATION_TRANSITION_NOT_ALLOWED"
    );

    private RequestOutcome() { }

    public static void problem(HttpServletRequest request, String code) {
        if (request.getAttribute(ATTRIBUTE) == null) {
            request.setAttribute(ATTRIBUTE, CODES.contains(code) ? code : "BUSINESS_CONFLICT");
        }
    }

    public static void failure(HttpServletRequest request, Throwable failure) {
        // A bounded traversal also handles unusual cyclic exception causes safely.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (sql.getErrorCode() == 1213) {
                    request.setAttribute(ATTRIBUTE, "DB_DEADLOCK");
                    return;
                }
                if (sql.getErrorCode() == 1205) {
                    request.setAttribute(ATTRIBUTE, "DB_LOCK_TIMEOUT");
                    return;
                }
                if (sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                    request.setAttribute(ATTRIBUTE, "DB_CONNECTION");
                    return;
                }
            }
        }
    }

    public static String get(HttpServletRequest request, int status) {
        Object code = request.getAttribute(ATTRIBUTE);
        if (code instanceof String value && (CODES.contains(value) || value.equals("BUSINESS_CONFLICT"))) {
            return value;
        }
        return status >= 500 ? "INTERNAL_ERROR" : status == 401 ? "AUTHENTICATION_REQUIRED"
            : status == 403 ? "ACCESS_DENIED" : status >= 400 ? "CLIENT_ERROR" : "SUCCESS";
    }
}
