package com.chat99.server.feedback;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum FeedbackType {
    SUGGESTION("suggestion"),
    BUG("bug"),
    OTHER("other");

    private final String apiCode;

    FeedbackType(String apiCode) {
        this.apiCode = apiCode;
    }

    @JsonValue
    public String getApiCode() {
        return apiCode;
    }

    @JsonCreator
    public static FeedbackType fromApiCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("type required");
        }
        String v = value.trim();
        for (FeedbackType t : values()) {
            if (t.apiCode.equalsIgnoreCase(v) || t.name().equalsIgnoreCase(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown feedback type: " + value);
    }
}
