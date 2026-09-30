package com.slotq.integration.operations.recovery;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=OperationsRecoveryController.class)
class OperationsRecoveryAdvice {
    @ExceptionHandler(RecoveryProblem.class)
    ResponseEntity<Map<String,String>> problem(RecoveryProblem error) {
        return ResponseEntity.status(error.status()).header("Cache-Control","no-store").body(Map.of("code",error.code()));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Map<String,String>> invalid(Exception error) {
        return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("code","INVALID_RECOVERY_REQUEST"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,String>> unavailable(Exception error) {
        return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("code","OPERATIONS_UNAVAILABLE"));
    }
}
