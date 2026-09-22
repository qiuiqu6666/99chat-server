package com.chat99.sangong.common;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeFmt {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
    private TimeFmt() {}

    public static String iso(Instant instant) {
        if (instant == null) {
            return null;
        }
        return ISO.format(instant.atZone(ZoneId.systemDefault()));
    }
}
