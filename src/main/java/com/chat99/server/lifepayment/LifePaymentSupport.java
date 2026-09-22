package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class LifePaymentSupport {

    private static final DateTimeFormatter DISPLAY_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private static final ObjectMapper JSON = new ObjectMapper();

    private LifePaymentSupport() {}

    public static String formatTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return DISPLAY_TIME.format(instant);
    }

    public static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static boolean eqIgnoreBlank(String a, String b) {
        return blankToEmpty(a).equals(blankToEmpty(b));
    }

    public static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            throw LifePaymentExceptions.badRequest("invalid_phone");
        }
        String s = raw.trim().replace(" ", "").replace("-", "");
        if (s.startsWith("+86")) {
            s = s.substring(3);
        } else if (s.startsWith("86") && s.length() == 13) {
            s = s.substring(2);
        }
        if (!s.matches("^1\\d{10}$")) {
            throw LifePaymentExceptions.badRequest("invalid_phone");
        }
        return s;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String maskAccount(String accountNo) {
        if (accountNo == null || accountNo.length() < 6) {
            return accountNo;
        }
        int keepHead = Math.min(4, accountNo.length() / 2);
        int keepTail = Math.min(4, accountNo.length() - keepHead);
        return accountNo.substring(0, keepHead) + "****" + accountNo.substring(accountNo.length() - keepTail);
    }

    public static BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw LifePaymentExceptions.badRequest("invalid_amount");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static long yuanToFen(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static String newOrderNo(ServiceType serviceType) {
        return serviceType.name() + "-" + shortId();
    }

    public static String newTaskNo(String prefix) {
        return prefix + "-" + shortId();
    }

    public static String newQueryNo() {
        return "query-" + shortId();
    }

    private static String shortId() {
        long n = Math.abs(ThreadLocalRandom.current().nextLong());
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return Long.toString(n, 36) + uuid.substring(0, 6);
    }

    public static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    public static String joinServiceTypes(List<ServiceType> types) {
        StringBuilder sb = new StringBuilder();
        for (ServiceType t : types) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(t.name());
        }
        return sb.toString();
    }

    public static List<ServiceType> parseServiceTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of(ServiceType.mobile, ServiceType.water, ServiceType.electric, ServiceType.gas);
        }
        return raw.stream().map(ServiceType::require).distinct().toList();
    }

    public static List<ServiceType> parseServiceTypesCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(ServiceType::require)
            .distinct()
            .toList();
    }

    public static String serviceTitle(ServiceType type) {
        return switch (type) {
            case mobile -> "手机充值";
            case water -> "水费";
            case electric -> "电费";
            case gas -> "燃气费";
        };
    }

    public static String serviceSubtitle(ServiceType type) {
        return switch (type) {
            case mobile -> "三网通充";
            case water -> "水务缴费";
            case electric -> "国网缴费";
            case gas -> "燃气缴费";
        };
    }

    public static String amountLabel(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString() + "元";
    }

    public static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String guessCarrier(String phone) {
        if (phone == null || phone.length() < 3) {
            return "";
        }
        String prefix = phone.substring(0, 3);
        if (List.of("134", "135", "136", "137", "138", "139", "147", "150", "151", "152", "157", "158", "159",
            "172", "178", "182", "183", "184", "187", "188", "195", "197", "198").contains(prefix)) {
            return "中国移动";
        }
        if (List.of("130", "131", "132", "145", "155", "156", "166", "175", "176", "185", "186", "196").contains(prefix)) {
            return "中国联通";
        }
        if (List.of("133", "149", "153", "173", "174", "177", "180", "181", "189", "190", "191", "193", "199").contains(prefix)) {
            return "中国电信";
        }
        return "";
    }

    public static String providerCodeOf(ServiceType serviceType, String cityCode, String cityName, String providerName) {
        // Chinese-only names strip to empty under ASCII slug rules; keep a stable hash suffix.
        String cityKey = blankToEmpty(cityCode);
        if (cityKey.isBlank()) {
            cityKey = blankToEmpty(cityName);
        }
        String cityPart = slugAscii(cityKey);
        if (cityPart.isBlank()) {
            cityPart = hexHash(cityKey);
        } else if (cityPart.length() > 16) {
            cityPart = cityPart.substring(0, 16);
        }
        String nameKey = blankToEmpty(providerName);
        String namePart = slugAscii(nameKey);
        if (namePart.isBlank()) {
            namePart = hexHash(nameKey);
        } else if (namePart.length() > 24) {
            namePart = namePart.substring(0, 24);
        }
        String cleaned = (serviceType.name() + "_" + cityPart + "_" + namePart).toLowerCase(Locale.ROOT);
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60);
        }
        return cleaned.isBlank() ? "provider_" + shortId() : cleaned;
    }

    private static String slugAscii(String value) {
        return blankToEmpty(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String hexHash(String value) {
        return String.format("%08x", blankToEmpty(value).hashCode());
    }
}
