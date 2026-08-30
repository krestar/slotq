package com.slotq.web;

import java.io.IOException;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ProductApiSecurityProblemWriter {

    private final ObjectMapper objectMapper;

    public ProductApiSecurityProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void authenticationRequired(HttpServletRequest request, HttpServletResponse response,
                                       Exception exception) throws IOException {
        write(response, ApiProblem.of(401, "Authentication required",
            "Authentication is required to access this resource.", request.getRequestURI(),
            "AUTHENTICATION_REQUIRED"));
    }

    public void accessDenied(HttpServletRequest request, HttpServletResponse response,
                             Exception exception) throws IOException {
        write(response, ApiProblem.of(403, "Access denied",
            "You do not have permission to access this resource.", request.getRequestURI(),
            "ACCESS_DENIED"));
    }

    private void write(HttpServletResponse response, ApiProblem problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
