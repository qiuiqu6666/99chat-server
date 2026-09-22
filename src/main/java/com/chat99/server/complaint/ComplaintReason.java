/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.complaint;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ComplaintReason {
    SPAM("spam", "\u5783\u573e\u4fe1\u606f"),
    HARASSMENT("harassment", "\u9a9a\u6270\u8fb1\u9a82"),
    FRAUD("fraud", "\u8bc8\u9a97"),
    PORNOGRAPHY("pornography", "\u8272\u60c5\u4f4e\u4fd7"),
    VIOLENCE("violence", "\u66b4\u529b\u5a01\u80c1"),
    ILLEGAL("illegal", "\u8fdd\u6cd5\u8fdd\u89c4"),
    OTHER("other", "\u5176\u4ed6");

    private final String apiCode;
    private final String label;

    private ComplaintReason(String apiCode, String label) {
        this.apiCode = apiCode;
        this.label = label;
    }

    @JsonValue
    public String getApiCode() {
        return this.apiCode;
    }

    public String label() {
        return this.label;
    }

    @JsonCreator
    public static ComplaintReason fromApiCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("reason required");
        }
        String v = value.trim();
        for (ComplaintReason r : ComplaintReason.values()) {
            if (!r.apiCode.equalsIgnoreCase(v) && !r.name().equalsIgnoreCase(v)) continue;
            return r;
        }
        throw new IllegalArgumentException("Unknown complaint reason: " + value);
    }
}
