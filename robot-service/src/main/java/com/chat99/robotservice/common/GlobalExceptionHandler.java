package com.chat99.robotservice.common;

import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 与主服务 GlobalExceptionHandler 保持相同的错误 JSON 格式（robot 业务用到的子集）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleRse(ResponseStatusException e) {
        String reason = e.getReason() != null ? e.getReason() : "ERROR";
        log.warn("robot api rejected status={} code={}", e.getStatusCode().value(), reason);
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
            "code", reason,
            "message", messageForCode(reason)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIae(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "code", "INVALID_INPUT",
            "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ":" + fe.getDefaultMessage())
            .collect(Collectors.joining(","));
        return ResponseEntity.badRequest().body(Map.of(
            "code", "INVALID_INPUT",
            "message", detail.isEmpty() ? "INVALID_INPUT" : detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        String detail = e.getMostSpecificCause() != null
            ? e.getMostSpecificCause().getMessage()
            : e.getMessage();
        if (detail != null && detail.length() > 200) {
            detail = detail.substring(0, 200);
        }
        return ResponseEntity.badRequest().body(Map.of(
            "code", "INVALID_INPUT",
            "message", detail != null && !detail.isBlank() ? detail : "INVALID_INPUT"));
    }

    private static String messageForCode(String code) {
        if (code == null) {
            return "ERROR";
        }
        return switch (code) {
            case "UNAUTHORIZED" -> "请先登录";
            default -> code;
        };
    }
}
