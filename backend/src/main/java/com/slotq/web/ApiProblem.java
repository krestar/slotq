package com.slotq.web;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    Map<String, String> fieldErrors
) {

    public static ApiProblem of(int status, String title, String detail,
                                String instance, String code) {
        return new ApiProblem("/problems/" + code.toLowerCase().replace('_', '-'),
            title, status, detail, instance, code, null);
    }

    public static ApiProblem validation(String detail, String instance,
                                        Map<String, String> fieldErrors) {
        return new ApiProblem("/problems/validation-failed", "Validation failed", 400,
            detail, instance, "VALIDATION_FAILED", fieldErrors);
    }
}
