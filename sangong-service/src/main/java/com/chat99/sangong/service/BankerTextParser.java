package com.chat99.sangong.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 快速定庄文本解析（4.9999、4/99999、4.5万、4）。 */
@Service
public class BankerTextParser {

    public record ParsedBanker(int door, int limit, boolean limited) {}

    private static final Pattern DOOR_LIMIT =
        Pattern.compile("^(\\d+)" + GameCommandTextHelper.SEPARATOR + "(\\d+(?:万)?)$");

    public ParsedBanker parse(String input) {
        String text = GameCommandTextHelper.normalizeCommandText(input);
        if (text.isEmpty()) {
            return null;
        }
        Matcher m = DOOR_LIMIT.matcher(text);
        if (m.matches()) {
            Integer door = parseDoor(m.group(1));
            if (door == null) {
                return null;
            }
            Integer limit = GameCommandTextHelper.parseAmount(m.group(2));
            if (limit == null || limit < 0) {
                return null;
            }
            return new ParsedBanker(door, limit, limit > 0);
        }
        Integer door = parseDoor(text);
        if (door == null) {
            return null;
        }
        return new ParsedBanker(door, 0, false);
    }

    private Integer parseDoor(String doorPart) {
        if (doorPart.equals("10")) {
            return 10;
        }
        if (doorPart.length() == 1 && doorPart.charAt(0) >= '1' && doorPart.charAt(0) <= '9') {
            return doorPart.charAt(0) - '0';
        }
        return null;
    }
}
