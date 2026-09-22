package com.chat99.server.wallet;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 对外 API 币种代码：USDT、99（平台币）、TRX、CNY（运营调账与平台币共用分）。 */
public enum WalletCurrency {
    USDT("USDT"),
    PLATFORM("99"),
    TRX("TRX"),
    CNY("CNY");

    private final String apiCode;

    WalletCurrency(String apiCode) {
        this.apiCode = apiCode;
    }

    @JsonValue
    public String getApiCode() {
        return apiCode;
    }

    @JsonCreator
    public static WalletCurrency fromApiCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("currency required");
        }
        String v = value.trim();
        for (WalletCurrency c : values()) {
            if (c.apiCode.equalsIgnoreCase(v)) {
                return c;
            }
        }
        if ("PLATFORM".equalsIgnoreCase(v)) {
            return PLATFORM;
        }
        throw new IllegalArgumentException("Unknown currency: " + value);
    }
}
