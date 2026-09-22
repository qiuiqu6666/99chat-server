package com.chat99.server.adminapi;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackageClasses = {
    AdminUsersController.class,
    AdminAuthController.class,
    AdminProfileController.class,
    AdminLogsController.class,
    AdminSystemConfigController.class,
    AdminDashboardController.class,
    AdminFinanceController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
            "error", "forbidden",
            "message", "forbidden"));
    }

    @ExceptionHandler(AdminApiException.class)
    public ResponseEntity<Map<String, String>> handleAdmin(AdminApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of(
            "error", e.error(),
            "message", e.getMessage() != null ? e.getMessage() : e.error()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleRse(ResponseStatusException e) {
        String reason = e.getReason() != null ? e.getReason() : "error";
        String error = reason.toLowerCase().replace('_', '_');
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status).body(Map.of(
            "error", error,
            "message", reason));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> concurrentWallet() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "error", "concurrent_wallet_update",
            "message", "concurrent_wallet_update"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ":" + fe.getDefaultMessage())
            .collect(Collectors.joining(","));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
            "error", "validation_error",
            "message", detail.isEmpty() ? "validation_error" : detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
            "error", "validation_error",
            "message", "invalid request body"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> illegal(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
            "error", "validation_error",
            "message", e.getMessage() != null ? e.getMessage() : "validation_error"));
    }
}
