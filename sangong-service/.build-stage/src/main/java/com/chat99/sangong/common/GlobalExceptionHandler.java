package com.chat99.sangong.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final MediaType TEXT_EVENT_STREAM = MediaType.valueOf("text/event-stream");

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BusinessException e, HttpServletRequest req) {
        if (isAsyncOrStreaming(req)) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        if (e instanceof InsufficientBalanceException ibe) {
            body.put("balance", ibe.getBalance());
        }
        return ResponseEntity.status(e.getHttpStatus()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIae(IllegalArgumentException e, HttpServletRequest req) {
        if (isAsyncOrStreaming(req)) {
            return null;
        }
        return ResponseEntity.badRequest().body(Map.of("ok", false, "code", "INVALID_INPUT", "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValid(MethodArgumentNotValidException e, HttpServletRequest req) {
        if (isAsyncOrStreaming(req)) {
            return null;
        }
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ":" + fe.getDefaultMessage()).collect(Collectors.joining(","));
        return ResponseEntity.badRequest().body(Map.of("ok", false, "code", "INVALID_INPUT", "message", detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        if (isAsyncOrStreaming(req)) {
            return null;
        }
        return ResponseEntity.badRequest().body(Map.of("ok", false, "code", "INVALID_INPUT", "message", "INVALID_INPUT"));
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleAsyncTimeout(AsyncRequestTimeoutException e, HttpServletRequest req) {
        // 异步请求（典型为 SSE）已经处于超时清理阶段，response 提交后再写 body 会抛
        // HttpMessageNotWritableException，并被外层 DefaultHandlerExceptionResolver 重复警告。
        // 这里直接返回 null，让 Spring 走默认的「静默忽略」路径。
        return null;
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e, HttpServletRequest req) {
        if (isAsyncOrStreaming(req)) {
            // SSE / 异步响应的 response 已经 commit（甚至写出 `event: state`），再尝试 JSON 序列化
            // 会撞 HttpMessageNotWritableException: No converter for ... with preset Content-Type
            // 'text/event-stream'。这里直接返回 null，由 Spring 使用默认「response committed, 忽略」语义。
            return null;
        }
        return ResponseEntity.status(422).body(Map.of(
            "ok", false,
            "code", "ERROR",
            "message", e.getMessage() == null ? "ERROR" : e.getMessage()));
    }

    private static boolean isAsyncOrStreaming(HttpServletRequest req) {
        if (req == null) {
            return false;
        }
        if (req.isAsyncStarted()) {
            return true;
        }
        String accept = req.getHeader("Accept");
        if (accept != null && accept.contains("text/event-stream")) {
            return true;
        }
        String contentType = req.getHeader("Content-Type");
        if (contentType != null && contentType.contains("text/event-stream")) {
            return true;
        }
        // AdminRealtimeController 当前仅 /api/v1/admin/events/stream 一个 SSE 入口
        String uri = req.getRequestURI();
        return uri != null && uri.endsWith("/events/stream");
    }
}