package com.github.huchangjun118.schemamigrationplannerjava.api;

import com.github.huchangjun118.schemamigrationplannerjava.auth.AccessDeniedException;
import com.github.huchangjun118.schemamigrationplannerjava.lease.LeaseLostException;
import com.github.huchangjun118.schemamigrationplannerjava.model.SchemaConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps domain exceptions to distinguishable HTTP error responses.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(SchemaConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(SchemaConflictException e) {
        Map<String, Object> body = base("SCHEMA_CONFLICT", e.getMessage());
        body.put("conflicts", e.getConflicts());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        Map<String, Object> body = base("FORBIDDEN", e.getMessage());
        body.put("reason", e.getReason().name());
        body.put("actor", e.getActor());
        body.put("requiredRole", e.getRequiredRole().name());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(LeaseLostException.class)
    public ResponseEntity<Map<String, Object>> lease(LeaseLostException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(base("LEASE_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(base("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(base("ILLEGAL_STATE", e.getMessage()));
    }

    private Map<String, Object> base(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }
}
