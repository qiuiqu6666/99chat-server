package com.chat99.server.common;

import com.chat99.server.chatattachment.ChatAttachmentAccessLogFilter;
import com.chat99.server.im.ImRestException;
import com.chat99.server.group.GroupJoinLimitExceededException;
import com.chat99.server.sms.SmsCodeStore;
import com.chat99.server.sms.SmsRateLimiter;
import com.chat99.server.sms.SmsbaoClient;
import com.chat99.server.user.NicknameCooldownException;
import com.chat99.server.user.SearchBlockedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SmsRateLimiter.RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> handleRl(SmsRateLimiter.RateLimitedException e) {
        return ResponseEntity.status(429).body(Map.of(
            "code", "RATE_LIMITED",
            "message", "too many requests",
            "retryAfter", e.retryAfterSeconds));
    }

    @ExceptionHandler(SmsCodeStore.DeviceSendLimitException.class)
    public ResponseEntity<Map<String, Object>> handleDeviceSendLimit(SmsCodeStore.DeviceSendLimitException e) {
        return ResponseEntity.status(429).body(Map.of(
            "code", "RATE_LIMITED",
            "message", "too many requests"));
    }

    @ExceptionHandler(SmsbaoClient.SmsSendException.class)
    public ResponseEntity<Map<String, Object>> handleSms(SmsbaoClient.SmsSendException e) {
        return ResponseEntity.status(502).body(Map.of("code", e.getMessage(), "message", "sms provider failure"));
    }

    @ExceptionHandler(SearchBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleSearchBlocked(SearchBlockedException e) {
        return ResponseEntity.status(429).body(Map.of(
            "code", "SEARCH_BLOCKED",
            "message", "too many missed searches",
            "retryAfter", e.retryAfterSeconds));
    }

    @ExceptionHandler(ImRestException.class)
    public ResponseEntity<Map<String, Object>> handleIm(ImRestException e) {
        HttpStatus status = e.imErrorCode() == 10023 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of(
            "code", "IM_REST_ERROR",
            "message", e.getMessage(),
            "imErrorCode", e.imErrorCode()));
    }

    @ExceptionHandler(NicknameCooldownException.class)
    public ResponseEntity<Map<String, Object>> handleNicknameCooldown(NicknameCooldownException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "code", "NICKNAME_COOLDOWN",
            "message", "nickname can only be changed once every 7 days",
            "nextChangeableAt", e.nextChangeableAt().toString()));
    }

    @ExceptionHandler(GroupJoinLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleJoinLimit(GroupJoinLimitExceededException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getCode());
        body.put("message", ApiErrorMessages.messageForCode(e.getCode()));
        body.put("overLimitUsers", e.getOverLimitUsers().stream()
            .map(u -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("userId", u.userId());
                row.put("used", u.used());
                row.put("max", u.max());
                row.put("limitType", u.limitType());
                return row;
            })
            .toList());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleRse(ResponseStatusException e) {
        String reason = e.getReason() != null ? e.getReason() : "ERROR";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", reason);
        body.put("message", ApiErrorMessages.messageForCode(reason));
        // 客户端对分组写操作认 ok:false 并回滚本地乐观更新
        if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()
            || "FOLDER_NAME_CONFLICT".equals(reason)) {
            body.put("ok", false);
        }
        if ("RATE_LIMITED".equals(reason)) {
            body.put("retryAfter", 60);
        }
        String requestId = ChatAttachmentAccessLogFilter.currentRequestId();
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return ResponseEntity.status(e.getStatusCode()).body(body);
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
}
