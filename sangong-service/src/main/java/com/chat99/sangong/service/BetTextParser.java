package com.chat99.sangong.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 下注指令解析：与 PHP BetTextParser 一致。 */
@Service
public class BetTextParser {

    public record Parsed(String mode, String allIdleKeyword, List<Integer> doors, int amount) {
        public boolean isAllIdle() { return "all_idle".equals(mode); }
    }

    public record Resolved(List<Integer> doors, int amount) {}

    private static final Pattern ALL_IDLE =
        Pattern.compile("^(全|公)" + GameCommandTextHelper.SEPARATOR + "?(\\d+)$");
    private static final Pattern DOORS_AMOUNT =
        Pattern.compile("^(\\d+)" + GameCommandTextHelper.SEPARATOR + "(\\d+)$");

    public Parsed parse(String input) {
        return parseWithDoorCount(input, 10);
    }

    /**
     * 与 {@link #parse(String)} 行为一致,只是会把真实 doorCount 透传给
     * {@link #parseDoors(String, int)}:doorCount&lt;10 时跳过 0 字符,
     * doorCount&gt;=10 时 0 仍视作第 10 门(向后兼容)。
     */
    private Parsed parseWithDoorCount(String input, int doorCount) {
        String text = GameCommandTextHelper.normalizeCommandText(input);
        if (text.isEmpty()) {
            return null;
        }

        Matcher allIdle = ALL_IDLE.matcher(text);
        if (allIdle.matches()) {
            int amount = safeInt(allIdle.group(2));
            if (amount <= 0) {
                return null;
            }
            return new Parsed("all_idle", allIdle.group(1), List.of(), amount);
        }

        Matcher m = DOORS_AMOUNT.matcher(text);
        if (!m.matches()) {
            return null;
        }
        List<Integer> doors = parseDoors(m.group(1), doorCount);
        if (doors == null) {
            return null;
        }
        int amount = safeInt(m.group(2));
        if (amount <= 0) {
            return null;
        }
        return new Parsed("doors", null, doors, amount);
    }

    public Resolved resolve(Parsed parsed, int doorCount, Integer bankerDoor) {
        if (parsed == null) {
            return null;
        }
        int amount = parsed.amount();
        if (amount <= 0) {
            return null;
        }

        if (parsed.isAllIdle()) {
            if (bankerDoor == null || bankerDoor < 1 || bankerDoor > doorCount) {
                return null;
            }
            List<Integer> doors = new ArrayList<>();
            for (int door = 1; door <= doorCount; door++) {
                if (door != bankerDoor) {
                    doors.add(door);
                }
            }
            if (doors.isEmpty()) {
                return null;
            }
            return new Resolved(doors, amount);
        }

        List<Integer> doors = new ArrayList<>(parsed.doors());
        if (doors.isEmpty()) {
            return null;
        }
        doors.removeIf(door -> door < 1 || door > doorCount);
        if (doors.isEmpty()) {
            return null;
        }
        if (bankerDoor != null && bankerDoor >= 1 && bankerDoor <= doorCount) {
            doors.removeIf(door -> door.intValue() == bankerDoor.intValue());
        }
        if (doors.isEmpty()) {
            return null;
        }
        return new Resolved(doors, amount);
    }

    public String resolveRejectReason(Parsed parsed, int doorCount, Integer bankerDoor) {
        if (resolve(parsed, doorCount, bankerDoor) != null) {
            return "";
        }
        if (parsed != null && parsed.isAllIdle()) {
            if (bankerDoor == null || bankerDoor < 1 || bankerDoor > doorCount) {
                return "尚未定庄门，无法使用全门下注";
            }
        }
        if (touchesOnlyBankerDoor(parsed, doorCount, bankerDoor)) {
            return "庄门不可下注";
        }
        return "下注指令无效";
    }

    public boolean touchesOnlyBankerDoor(Parsed parsed, int doorCount, Integer bankerDoor) {
        if (parsed == null || bankerDoor == null || bankerDoor < 1 || bankerDoor > doorCount) {
            return false;
        }
        if (parsed.isAllIdle()) {
            return false;
        }
        List<Integer> doors = parsed.doors();
        if (doors.isEmpty()) {
            return false;
        }
        boolean hasInRange = false;
        for (int door : doors) {
            if (door < 1 || door > doorCount) {
                continue;
            }
            hasInRange = true;
            if (door != bankerDoor) {
                return false;
            }
        }
        return hasInRange;
    }

    public Parsed parseAndResolve(String text, int doorCount, Integer bankerDoor) {
        Parsed parsed = parseWithDoorCount(text, doorCount);
        if (parsed == null) {
            return null;
        }
        Resolved resolved = resolve(parsed, doorCount, bankerDoor);
        if (resolved == null) {
            return null;
        }
        return new Parsed(parsed.mode(), parsed.allIdleKeyword(), resolved.doors(), resolved.amount());
    }

    public String allIdleKeyword(Parsed parsed) {
        if (parsed == null || !parsed.isAllIdle()) {
            return "";
        }
        return "公".equals(parsed.allIdleKeyword()) ? "公" : "全";
    }

    /**
     * 多门连写规则:
     * - 整体字符串为 {@code "10"} 时视为单门 10(避免拆成 [1, 10])
     * - 其它情况按字符解析:
     *   - {@code doorCount >= 10} 时,字符 {@code '0'} 表示第 10 门(向后兼容)
     *   - {@code doorCount < 10} 时,字符 {@code '0'} 直接跳过(不录入),
     *     避免 024 被误解析为 [10, 2, 4] 后又在 resolve 阶段被剔除成空集,
     *     期望行为是 024 → [2, 4]。
     * - resolve 阶段会按当前门数剔除越界门和庄门。
     */
    private List<Integer> parseDoors(String doorsStr, int doorCount) {
        if (doorsStr.isEmpty() || !doorsStr.chars().allMatch(Character::isDigit)) {
            return null;
        }
        if (doorsStr.equals("10")) {
            return List.of(10);
        }
        List<Integer> doors = new ArrayList<>();
        for (int i = 0; i < doorsStr.length(); i++) {
            char c = doorsStr.charAt(i);
            if (c == '0') {
                if (doorCount >= 10) {
                    doors.add(10);
                }
                continue;
            }
            doors.add(c - '0');
        }
        return doors;
    }

    public String formatDoorsLabel(List<Integer> doors, boolean allIdle, String allIdleKeyword) {
        if (allIdle) {
            return "公".equals(allIdleKeyword) ? "公" : "全";
        }
        if (doors == null || doors.isEmpty()) {
            return "";
        }
        if (doors.size() == 1 && doors.get(0) == 10) {
            return "10";
        }
        StringBuilder sb = new StringBuilder();
        for (int d : doors) {
            sb.append(d);
        }
        return sb.toString();
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
