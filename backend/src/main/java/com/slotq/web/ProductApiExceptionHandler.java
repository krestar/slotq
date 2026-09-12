package com.slotq.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import com.slotq.availability.application.AvailabilityValidationException;
import com.slotq.auth.application.AccessDeniedException;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.booking.application.HoldIdempotencyValidationException;
import com.slotq.booking.application.ProductApiException;
import com.slotq.booking.application.SlotInventoryConflictException;
import com.slotq.booking.application.SlotInventoryNotAllowedException;
import com.slotq.management.application.ManagementValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class ProductApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> validation(MethodArgumentNotValidException exception,
                                          HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return problem(ApiProblem.validation("One or more request fields are invalid.",
            request.getRequestURI(), Map.copyOf(fieldErrors)));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    ResponseEntity<ApiProblem> malformedRequest(Exception exception, HttpServletRequest request) {
        return problem(ApiProblem.validation("The request format is invalid.",
            request.getRequestURI(), Map.of()));
    }

    @ExceptionHandler(ManagementValidationException.class)
    ResponseEntity<ApiProblem> managementValidation(
        ManagementValidationException exception,
        HttpServletRequest request
    ) {
        return problem(ApiProblem.validation(
            "One or more request fields are invalid.",
            request.getRequestURI(),
            exception.fieldErrors()
        ));
    }

    @ExceptionHandler(AvailabilityValidationException.class)
    ResponseEntity<ApiProblem> availabilityValidation(
        AvailabilityValidationException exception,
        HttpServletRequest request
    ) {
        return problem(ApiProblem.validation(
            "One or more request fields are invalid.",
            request.getRequestURI(),
            exception.fieldErrors()
        ));
    }

    @ExceptionHandler(HoldIdempotencyValidationException.class)
    ResponseEntity<ApiProblem> holdIdempotencyValidation(
        HoldIdempotencyValidationException exception,
        HttpServletRequest request
    ) {
        return problem(ApiProblem.validation(
            "One or more request headers are invalid.",
            request.getRequestURI(),
            exception.fieldErrors()
        ));
    }

    @ExceptionHandler(SlotInventoryConflictException.class)
    ResponseEntity<ApiProblem> slotConflict(
        SlotInventoryConflictException exception,
        HttpServletRequest request
    ) {
        return problem(ApiProblem.of(409, "Slot inventory conflict",
            "The requested slot overlaps an existing slot.", request.getRequestURI(),
            "SLOT_INVENTORY_CONFLICT"));
    }

    @ExceptionHandler(SlotInventoryNotAllowedException.class)
    ResponseEntity<ApiProblem> slotNotAllowed(
        SlotInventoryNotAllowedException exception,
        HttpServletRequest request
    ) {
        return problem(ApiProblem.of(409, "Slot inventory not allowed",
            "A slot cannot be created for the selected resource.", request.getRequestURI(),
            "SLOT_INVENTORY_NOT_ALLOWED"));
    }

    @ExceptionHandler({ResourceNotFoundException.class, NoSuchElementException.class,
        NoResourceFoundException.class})
    ResponseEntity<ApiProblem> notFound(Exception exception, HttpServletRequest request) {
        return problem(ApiProblem.of(404, "Resource not found",
            "The requested resource was not found.", request.getRequestURI(), "RESOURCE_NOT_FOUND"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiProblem> accessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return problem(ApiProblem.of(403, "Access denied",
            "You do not have permission to access this resource.", request.getRequestURI(),
            "ACCESS_DENIED"));
    }

    @ExceptionHandler(ProductApiException.class)
    ResponseEntity<ApiProblem> productConflict(ProductApiException exception,
                                               HttpServletRequest request) {
        return problem(ApiProblem.of(409, exception.error().title(), exception.error().detail(),
            request.getRequestURI(), exception.error().name()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> internalError(Exception exception, HttpServletRequest request) {
        return problem(ApiProblem.of(500, "Internal error",
            "An unexpected error occurred.", request.getRequestURI(), "INTERNAL_ERROR"));
    }

    private ResponseEntity<ApiProblem> problem(ApiProblem problem) {
        return ResponseEntity.status(HttpStatus.valueOf(problem.status()))
            .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }
}
