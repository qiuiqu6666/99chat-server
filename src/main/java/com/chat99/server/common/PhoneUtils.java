package com.chat99.server.common;

import org.springframework.stereotype.Component;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

@Component
public class PhoneUtils {

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    public Parsed parseE164(String raw) {
        if (raw == null || raw.isBlank() || !raw.startsWith("+")) {
            throw new IllegalArgumentException("phone must be E.164 (start with +)");
        }
        try {
            Phonenumber.PhoneNumber n = util.parse(raw, null);
            if (!util.isValidNumber(n)) {
                throw new IllegalArgumentException("invalid phone");
            }
            String e164 = util.format(n, PhoneNumberUtil.PhoneNumberFormat.E164);
            return new Parsed(e164, String.valueOf(n.getCountryCode()));
        } catch (NumberParseException e) {
            throw new IllegalArgumentException("invalid phone: " + e.getMessage());
        }
    }

    public Parsed parseWithRegion(String raw, String region) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("phone is blank");
        }
        try {
            Phonenumber.PhoneNumber n;
            if (raw.startsWith("+")) {
                n = util.parse(raw, null);
            } else if (raw.matches("^[0-9]+$")) {
                if (region == null || region.isBlank()) {
                    throw new IllegalArgumentException("region is required for local phone");
                }
                n = util.parse(raw, region.toUpperCase());
            } else {
                throw new IllegalArgumentException("invalid phone format");
            }
            if (!util.isValidNumber(n)) {
                throw new IllegalArgumentException("invalid phone");
            }
            String e164 = util.format(n, PhoneNumberUtil.PhoneNumberFormat.E164);
            return new Parsed(e164, String.valueOf(n.getCountryCode()));
        } catch (NumberParseException e) {
            throw new IllegalArgumentException("invalid phone: " + e.getMessage());
        }
    }

    public String mask(String e164) {
        if (e164 == null || e164.length() < 7) return e164;
        int keepTail = 4;
        int keepHead = Math.max(3, e164.length() - keepTail - 4);
        return e164.substring(0, keepHead) + "****" + e164.substring(e164.length() - keepTail);
    }

    public boolean isDomestic(String countryCode) {
        return "86".equals(countryCode);
    }

    public String regionForCountryCode(String countryCode) {
        try {
            String region = util.getRegionCodeForCountryCode(Integer.parseInt(countryCode));
            return region != null ? region : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** 后台批量创建曾写入的占位号（+8612 开头），或 phone 为空，均视为未绑定手机。 */
    public boolean hasBoundPhone(String e164) {
        return e164 != null && !e164.isBlank() && !isPlaceholderPhone(e164);
    }

    public boolean isPlaceholderPhone(String e164) {
        return e164 != null && e164.matches("^\\+8612\\d{9}$");
    }

    /**
     * 与客户端通讯录 SyncFingerprint 对齐：去空格/横线/括号；{@code 00} 转 {@code +}；
     * 11 位 {@code 1} 开头补 {@code +86}；已是 {@code +} 则按 E.164 解析。
     */
    public String normalizeContactPhone(String raw, String phoneCountry) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("phone is blank");
        }
        String s = raw.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "");
        if (s.startsWith("00")) {
            s = "+" + s.substring(2);
        } else if (!s.startsWith("+") && s.matches("^1\\d{10}$")) {
            s = "+86" + s;
        }
        if (s.startsWith("+")) {
            return parseE164(s).e164();
        }
        String region = (phoneCountry == null || phoneCountry.isBlank()) ? "CN" : phoneCountry;
        return parseWithRegion(s, region).e164();
    }

    public record Parsed(String e164, String countryCode) {}
}
