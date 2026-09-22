package com.chat99.server.feedback;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum FeedbackStatus {
    PENDING("pending"),
    PROCESSED("processed"),
    CLOSED("closed");

    private final String apiCode;

    FeedbackStatus(String apiCode) {
        this.apiCode = apiCode;
    }

    @JsonValue
    public String getApiCode() {
        return apiCode;
    }

    @JsonCreator
    public static FeedbackStatus fromApiCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        for (FeedbackStatus s : values()) {
            if (s.apiCode.equalsIgnoreCase(v) || s.name().equalsIgnoreCase(v)) {
                return s;
            }
        }
        return switch (v) {
            case "待处理" -> PENDING;
            case "已处理" -> PROCESSED;
            case "已关闭" -> CLOSED;
            default -> null;
        };
    }

    public String label() {
        return switch (this) {
            case PENDING -> "待处理";
            case PROCESSED -> "已处理";
            case CLOSED -> "已关闭";
        };
    }
}
