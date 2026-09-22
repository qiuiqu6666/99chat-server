/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.complaint;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ChatComplaintType {
    C2C("c2c"),
    GROUP("group");

    private final String apiCode;

    private ChatComplaintType(String apiCode) {
        this.apiCode = apiCode;
    }

    @JsonValue
    public String getApiCode() {
        return this.apiCode;
    }

    @JsonCreator
    public static ChatComplaintType fromApiCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("chat type required");
        }
        String v = value.trim();
        for (ChatComplaintType t : ChatComplaintType.values()) {
            if (!t.apiCode.equalsIgnoreCase(v) && !t.name().equalsIgnoreCase(v)) continue;
            return t;
        }
        throw new IllegalArgumentException("Unknown chat complaint type: " + value);
    }
}
