package com.chat99.server.messagearchive;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ChatMessageTableRouter {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);
    private static final int MAX_MONTH_SPAN = 12;

    public String tableSuffix(Instant instant) {
        return SUFFIX.format(instant == null ? Instant.now() : instant);
    }

    public String physicalTable(Instant instant) {
        return "chat_message_" + tableSuffix(instant);
    }

    public String physicalTable(long msgTimeMs) {
        return physicalTable(Instant.ofEpochMilli(msgTimeMs));
    }

    /** 从 anchor 所在月起向前 monthsBack 个月（含当月），用于撤回时定位分表。 */
    public List<String> physicalTablesAround(Instant anchor, int monthsBack) {
        Instant base = anchor == null ? Instant.now() : anchor;
        int span = Math.max(monthsBack, 0);
        List<String> tables = new ArrayList<>(span + 1);
        for (int i = 0; i <= span; i++) {
            Instant monthInstant = base.atZone(ZoneOffset.UTC).minusMonths(i).toInstant();
            tables.add(physicalTable(monthInstant));
        }
        return tables;
    }

    /**
     * 覆盖 [fromMs, toMs] 的月分表（按时间倒序列出）。
     * 跨度超过 {@link #MAX_MONTH_SPAN} 个月时从 toMs 所在月起截断。
     */
    public List<String> physicalTablesBetween(long fromMs, long toMs) {
        long lo = Math.min(fromMs, toMs);
        long hi = Math.max(fromMs, toMs);
        YearMonth start = YearMonth.from(Instant.ofEpochMilli(lo).atZone(ZoneOffset.UTC));
        YearMonth end = YearMonth.from(Instant.ofEpochMilli(hi).atZone(ZoneOffset.UTC));
        List<String> tables = new ArrayList<>();
        for (YearMonth m = end; !m.isBefore(start); m = m.minusMonths(1)) {
            tables.add("chat_message_" + m.format(DateTimeFormatter.ofPattern("yyyyMM")));
            if (tables.size() >= MAX_MONTH_SPAN) {
                break;
            }
        }
        return tables;
    }
}
