package com.chat99.server.telegram;

import com.chat99.server.group.GroupGameService;
import com.chat99.server.group.GroupProfile;
import com.chat99.server.group.GroupProfileRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Telegram 运维「开群特权」控制群（推荐）：
 * <pre>
 * 配对CP5Y-4EX1-AC9V
 * 开启@TGS#27PIAKM5CC
 * </pre>
 * 机器码是机器人数据真源；{@code 开启@…} 后是 IM 群（推荐完整 {@code @TGS#…} / {@code @TGS#_@TGS#…}）。
 * 直接发送完整群 ID 可查询 {@code game_enabled}。
 * 配对机器码（无群 ID）只选定会话机器码，不 bind；显式 {@code 配对码 @群} 或 {@code 开启@群} 才绑定。
 * 兼容旧写法：短码、{@code 配对码 @群ID}、{@code 开启@机器人账号 @群ID}、先发群 ID 再指令。
 * {@code 关闭@群ID} 仍只关闭 IM {@code game_enabled}。
 */
@Service
public class TelegramGroupGameControlService {

    private static final String GROUP_TOKEN =
        "(@TGS#_@TGS#[A-Za-z0-9_-]+"
            + "|@TGS#[A-Za-z0-9_-]+"
            + "|TGS#_@TGS#[A-Za-z0-9_-]+"
            + "|TGS#[A-Za-z0-9_-]+"
            + "|@[A-Za-z0-9_-]{6,32}"
            + "|[A-Za-z0-9_-]{6,32})";

    private static final Pattern PAIR = Pattern.compile(
        "^\\s*配对\\s*([0-9A-Za-z-]{14,})(?:\\s+" + GROUP_TOKEN + ")?\\s*$");

    /** 开启后主参数为群 ID（完整 TGS / 短码兼容）或旧「机器人账号」；可选第二段再跟群 ID。 */
    private static final Pattern ENABLE = Pattern.compile(
        "^\\s*开启\\s*" + GROUP_TOKEN + "(?:\\s+" + GROUP_TOKEN + ")?\\s*$");

    private static final Pattern DISABLE = Pattern.compile(
        "^\\s*(关闭|停用)\\s*" + GROUP_TOKEN + "\\s*$");

    private static final Pattern QUERY = Pattern.compile("^\\s*" + GROUP_TOKEN + "\\s*$");

    private final TelegramOpsProperties props;
    private final GroupGameService groupGameService;
    private final GroupProfileRepository groupProfileRepository;
    private final RobotMachineClient robotMachineClient;
    /** Telegram chatId → 最近选定的 IM 群 ID */
    private final ConcurrentHashMap<String, String> selectedGroupByChat = new ConcurrentHashMap<>();
    /** Telegram chatId → 最近选定的机器码（机器人数据租户键） */
    private final ConcurrentHashMap<String, String> selectedMachineByChat = new ConcurrentHashMap<>();

    public TelegramGroupGameControlService(
            TelegramOpsProperties props,
            GroupGameService groupGameService,
            GroupProfileRepository groupProfileRepository,
            RobotMachineClient robotMachineClient) {
        this.props = props;
        this.groupGameService = groupGameService;
        this.groupProfileRepository = groupProfileRepository;
        this.robotMachineClient = robotMachineClient;
    }

    public boolean shouldHandleMessage(String chatId, String text, boolean fromBot) {
        if (!props.isGameControlReady() || fromBot) {
            return false;
        }
        if (!chatIdMatches(chatId, props.gameControlChatId())) {
            return false;
        }
        return !extractCommandSegments(text).isEmpty();
    }

    public String handle(String chatId, String text) {
        List<String> segments = extractCommandSegments(text);
        if (segments.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            Optional<Parsed> command = parseCommand(segment);
            if (command.isEmpty()) {
                continue;
            }
            Parsed cmd = command.get();
            String reply = switch (cmd.type()) {
                case PAIR -> handlePair(chatId, cmd);
                case ENABLE -> handleEnable(chatId, cmd);
                case DISABLE -> handleDisable(cmd);
                case QUERY -> handleQuery(chatId, cmd);
            };
            if (reply == null || reply.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n——————\n\n");
            }
            sb.append(reply);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    public String handle(String text) {
        return handle(props.gameControlChatId(), text);
    }

    private String handlePair(String chatId, Parsed cmd) {
        String machineCode = cmd.machineCode().trim();
        selectedMachineByChat.put(chatId, machineCode);

        // 仅显式「配对码 @群ID」才 bind；禁止用会话旧群隐式 bind（会踩灭已开特权的群）
        String groupToken = cmd.groupToken();
        if (groupToken == null || groupToken.isBlank()) {
            return "✅ 已选定机器码（机器人数据以该码为准）\n"
                + "机器码: <code>" + esc(machineCode) + "</code>\n"
                + "下一步请发送: <code>开启@TGS#群完整ID</code>\n"
                + "例如: <code>开启@TGS#27PIAKM5CC</code>";
        }

        Optional<String> resolved = resolveGroupId(groupToken);
        if (resolved.isEmpty()) {
            return "❓ 未找到群组: <code>" + esc(groupToken) + "</code>\n"
                + "机器码已选定: <code>" + esc(machineCode) + "</code>\n"
                + "请改用: <code>配对" + esc(machineCode) + " @TGS#完整群ID</code>"
                + " 或 <code>开启@TGS#完整群ID</code>";
        }

        String groupId = resolved.get();
        Map<String, Object> result = robotMachineClient.bindGroup(machineCode, groupId);
        selectedGroupByChat.put(chatId, groupId);
        String name = groupName(groupId);
        return "✅ 配对成功（开群特权第 1 步）\n"
            + "群名: " + esc(name) + "\n"
            + "群ID: <code>" + esc(groupId) + "</code>\n"
            + "机器码: <code>" + esc(String.valueOf(result.get("machineCode"))) + "</code>\n"
            + "下一步请发送: <code>开启</code> + 完整群ID\n"
            + "例如: <code>开启" + esc(groupId) + "</code>";
    }

    private String handleEnable(String chatId, Parsed cmd) {
        EnableTarget target = resolveEnableTarget(chatId, cmd);
        if (target.error() != null) {
            return target.error();
        }
        String groupId = target.groupId();
        String machineCode = target.machineCode();
        String robotId = target.robotId();

        // 推荐路径：先配对机器码，再 开启@群 → 此处补 bind + enable（robotId=机器码）
        if (target.bindFirst()) {
            robotMachineClient.bindGroup(machineCode, groupId);
        }
        Map<String, Object> result = robotMachineClient.enableForGroup(groupId, robotId);
        groupGameService.setGameEnabled(groupId, true);
        selectedGroupByChat.put(chatId, groupId);
        if (machineCode != null && !machineCode.isBlank()) {
            selectedMachineByChat.put(chatId, machineCode);
        }
        String name = groupName(groupId);
        return "✅ 开群特权成功\n"
            + "群名: " + esc(name) + "\n"
            + "群ID: <code>" + esc(groupId) + "</code>\n"
            + "机器码: <code>" + esc(String.valueOf(result.get("machineCode"))) + "</code>\n"
            + "机器人数据键: <code>" + esc(String.valueOf(result.get("robotId"))) + "</code>\n"
            + "game_enabled: <b>true</b>";
    }

    /**
     * 解析开启目标：
     * <ul>
     *   <li>推荐：{@code 开启@TGS#…} / {@code 开启@TGS#_@TGS#…} → 群 + 已选机器码（robotId=机器码）</li>
     *   <li>兼容：短码 {@code 开启@27PIAKM5CC}</li>
     *   <li>兼容：{@code 开启@机器人账号}（且能解析成群失败）+ 已选群</li>
     *   <li>兼容：{@code 开启@机器人账号 @群ID}</li>
     * </ul>
     */
    private EnableTarget resolveEnableTarget(String chatId, Parsed cmd) {
        String primary = cmd.robotId();
        String explicitGroupToken = cmd.groupToken();

        if (explicitGroupToken != null && !explicitGroupToken.isBlank()) {
            String groupId = resolveGroupId(explicitGroupToken).orElse(null);
            if (groupId == null) {
                return EnableTarget.fail("❓ 未找到群组: <code>" + esc(explicitGroupToken) + "</code>");
            }
            return EnableTarget.ok(groupId, selectedMachineByChat.get(chatId), primary, false);
        }

        Optional<String> asGroup = resolveGroupId(primary);
        if (asGroup.isPresent()) {
            String groupId = asGroup.get();
            String machine = selectedMachineByChat.get(chatId);
            boolean needBind = true;
            try {
                Map<String, Object> st = robotMachineClient.groupStatus(groupId);
                boolean bound = Boolean.TRUE.equals(st.get("bound"));
                String boundMachine = st.get("machineCode") == null ? ""
                    : String.valueOf(st.get("machineCode")).trim();
                if ((machine == null || machine.isBlank()) && bound && !boundMachine.isBlank()) {
                    machine = boundMachine;
                }
                if (bound && machine != null && machine.equals(boundMachine)) {
                    needBind = false;
                }
            } catch (Exception ignored) {
                // 下面统一提示先配对 / 或走 bind
            }
            if (machine == null || machine.isBlank()) {
                return EnableTarget.fail("❓ 请先发送机器码：<code>配对xxxx-xxxx-xxxx</code>\n"
                    + "再发送: <code>开启" + esc(groupId) + "</code>");
            }
            return EnableTarget.ok(groupId, machine, machine, needBind);
        }

        String groupId = selectedGroupByChat.get(chatId);
        if (groupId == null) {
            return EnableTarget.fail("❓ 推荐写法：\n"
                + "<code>配对xxxx-xxxx-xxxx</code>\n"
                + "<code>开启@TGS#完整群ID</code>\n"
                + "例如: <code>开启@TGS#27PIAKM5CC</code>");
        }
        return EnableTarget.ok(groupId, selectedMachineByChat.get(chatId), primary, false);
    }

    private record EnableTarget(String groupId, String machineCode, String robotId, boolean bindFirst, String error) {
        static EnableTarget ok(String groupId, String machineCode, String robotId, boolean bindFirst) {
            return new EnableTarget(groupId, machineCode, robotId, bindFirst, null);
        }

        static EnableTarget fail(String error) {
            return new EnableTarget(null, null, null, false, error);
        }
    }

    private String handleDisable(Parsed cmd) {
        Optional<String> resolved = resolveGroupId(cmd.groupToken());
        if (resolved.isEmpty()) {
            return "❓ 未找到群组: <code>" + esc(cmd.groupToken()) + "</code>";
        }
        String groupId = resolved.get();
        groupGameService.setGameEnabled(groupId, false);
        String name = groupName(groupId);
        return "✅ 群游戏已关闭\n"
            + "群名: " + esc(name) + "\n"
            + "群ID: <code>" + esc(groupId) + "</code>\n"
            + "game_enabled: <b>false</b>\n"
            + "（机器码绑定仍保留；需重新 <code>开启" + esc(groupId) + "</code> 才会再开 game_enabled）";
    }

    private String handleQuery(String chatId, Parsed cmd) {
        Optional<String> resolved = resolveGroupId(cmd.groupToken());
        if (resolved.isEmpty()) {
            return "❓ 未找到群组: <code>" + esc(cmd.groupToken()) + "</code>";
        }
        String groupId = resolved.get();
        selectedGroupByChat.put(chatId, groupId);
        boolean gameOn = groupGameService.isGameEnabled(groupId);
        String name = groupName(groupId);
        StringBuilder sb = new StringBuilder();
        sb.append(gameOn ? "✅ 游戏开关: <b>已开启</b>\n" : "ℹ️ 游戏开关: <b>未开启</b>\n")
            .append("群名: ").append(esc(name)).append("\n")
            .append("群ID: <code>").append(esc(groupId)).append("</code>\n")
            .append("game_enabled: <b>").append(gameOn).append("</b>\n");
        try {
            Map<String, Object> st = robotMachineClient.groupStatus(groupId);
            boolean bound = Boolean.TRUE.equals(st.get("bound"));
            boolean enabled = Boolean.TRUE.equals(st.get("enabled"));
            sb.append("机器码绑定: <b>").append(bound ? "已配对" : "未配对").append("</b>\n");
            if (bound) {
                sb.append("开群特权: <b>").append(enabled ? "已开启" : "未开启").append("</b>\n");
                if (st.get("robotId") != null) {
                    sb.append("机器人数据键: <code>").append(esc(String.valueOf(st.get("robotId")))).append("</code>\n");
                }
                if (st.get("machineCode") != null) {
                    sb.append("机器码: <code>").append(esc(String.valueOf(st.get("machineCode")))).append("</code>\n");
                }
            }
            sb.append("\n开群特权指令:\n")
                .append("<code>配对xxxx-xxxx-xxxx</code>\n")
                .append("<code>开启").append(esc(groupId)).append("</code>");
        } catch (Exception e) {
            sb.append("机器码状态查询失败: ").append(esc(e.getMessage()));
        }
        return sb.toString();
    }

    private String groupName(String groupId) {
        return groupProfileRepository.findById(groupId)
            .map(GroupProfile::getGroupName)
            .filter(n -> n != null && !n.isBlank())
            .orElse(groupId);
    }

    static Optional<Parsed> parseCommand(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.trim();

        Matcher pair = PAIR.matcher(trimmed);
        if (pair.matches()) {
            return Optional.of(Parsed.pair(pair.group(1), pair.group(2)));
        }
        Matcher enable = ENABLE.matcher(trimmed);
        if (enable.matches()) {
            return Optional.of(Parsed.enable(
                normalizeGroupToken(enable.group(1)),
                enable.group(2) == null ? null : normalizeGroupToken(enable.group(2))));
        }
        Matcher disable = DISABLE.matcher(trimmed);
        if (disable.matches()) {
            return Optional.of(Parsed.disable(normalizeGroupToken(disable.group(2))));
        }
        Matcher query = QUERY.matcher(trimmed);
        if (query.matches()) {
            String token = normalizeGroupToken(query.group(1));
            if (token.equals("开启") || token.equals("关闭") || token.equals("停用") || token.equals("配对")) {
                return Optional.empty();
            }
            // 避免把「开启@xxx」误判；已由 ENABLE 处理
            return Optional.of(Parsed.query(token));
        }
        return Optional.empty();
    }

    /**
     * 支持一条消息里多条指令：换行分发，或同行 {@code 配对… 开启@…}。
     */
    static List<String> extractCommandSegments(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String trimmed = text.trim();
        if (parseCommand(trimmed).isPresent()) {
            return List.of(trimmed);
        }

        List<String> fromLines = new ArrayList<>();
        for (String line : trimmed.split("[\\r\\n]+")) {
            String t = line.trim();
            if (!t.isEmpty() && parseCommand(t).isPresent()) {
                fromLines.add(t);
            }
        }
        if (!fromLines.isEmpty()) {
            return fromLines;
        }

        // 同行粘贴：配对CP5Y-4EX1-AC9V 开启@TGS#27PIAKM5CC
        List<String> inline = new ArrayList<>();
        Matcher pairFind = Pattern.compile(
            "配对\\s*[0-9A-Za-z-]{14,}(?:\\s+" + GROUP_TOKEN + ")?").matcher(trimmed);
        while (pairFind.find()) {
            inline.add(pairFind.group().trim());
        }
        Matcher enableFind = Pattern.compile(
            "开启\\s*" + GROUP_TOKEN + "(?:\\s+" + GROUP_TOKEN + ")?").matcher(trimmed);
        while (enableFind.find()) {
            inline.add(enableFind.group().trim());
        }
        Matcher disableFind = Pattern.compile(
            "(?:关闭|停用)\\s*" + GROUP_TOKEN).matcher(trimmed);
        while (disableFind.find()) {
            inline.add(disableFind.group().trim());
        }
        return inline;
    }

    Optional<String> resolveGroupId(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String raw = normalizeGroupToken(token);
        String bare = bareGroupCode(raw);

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(raw);
        if (bare.matches("^m[A-Za-z0-9_-]+$")) {
            candidates.add(bare);
        } else {
            if (!raw.startsWith("@")) {
                candidates.add("@" + raw);
            }
            candidates.add("m" + bare);
            candidates.add("@TGS#" + bare);
            if (!bare.startsWith("m")) {
                candidates.add("@TGS#_@TGS#" + bare);
            }
        }

        for (String candidate : candidates) {
            if (groupProfileRepository.existsById(candidate)) {
                return Optional.of(candidate);
            }
        }

        List<GroupProfile> bySuffix = groupProfileRepository.findByGroupIdEndingWith(bare);
        if (bySuffix.isEmpty()) {
            return Optional.empty();
        }
        Optional<GroupProfile> preferred = bySuffix.stream()
            .filter(g -> g.getGroupId().equals("m" + bare)
                || g.getGroupId().equals(bare)
                || g.getGroupId().equals("@TGS#" + bare)
                || (g.getGroupId().equals("@TGS#_@TGS#" + bare) && !bare.startsWith("m")))
            .findFirst();
        if (preferred.isPresent()) {
            return Optional.of(preferred.get().getGroupId());
        }
        Optional<GroupProfile> migrated = bySuffix.stream()
            .filter(g -> g.getGroupId().startsWith("m") && !g.getGroupId().startsWith("@"))
            .findFirst();
        if (migrated.isPresent()) {
            return Optional.of(migrated.get().getGroupId());
        }
        if (bySuffix.size() == 1) {
            return Optional.of(bySuffix.get(0).getGroupId());
        }
        return Optional.empty();
    }

    static String normalizeGroupToken(String token) {
        String t = token == null ? "" : token.trim();
        while (t.startsWith("@@")) {
            t = t.substring(1);
        }
        if (t.startsWith("TGS#") || t.startsWith("TGS#_@TGS#")) {
            t = "@" + t;
        }
        return t;
    }

    static String bareGroupCode(String token) {
        String t = normalizeGroupToken(token);
        t = t.replaceFirst("^@TGS#_@TGS#", "");
        t = t.replaceFirst("^@TGS#", "");
        t = t.replaceFirst("^@", "");
        return t;
    }

    static boolean chatIdMatches(String actual, String configured) {
        if (actual == null || configured == null) {
            return false;
        }
        String a = actual.trim();
        String c = configured.trim();
        if (a.isEmpty() || c.isEmpty()) {
            return false;
        }
        if (a.equals(c)) {
            return true;
        }
        return normalizeTelegramChatId(a).equals(normalizeTelegramChatId(c));
    }

    static String normalizeTelegramChatId(String chatId) {
        String s = chatId.trim();
        if (s.startsWith("-100") && s.length() > 4) {
            return "-" + s.substring(4);
        }
        return s;
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    enum Type { PAIR, ENABLE, DISABLE, QUERY }

    record Parsed(Type type, String machineCode, String robotId, String groupToken) {
        static Parsed pair(String machineCode, String groupToken) {
            return new Parsed(Type.PAIR, machineCode, null, groupToken);
        }

        static Parsed enable(String robotId, String groupToken) {
            return new Parsed(Type.ENABLE, null, robotId, groupToken);
        }

        static Parsed disable(String groupToken) {
            return new Parsed(Type.DISABLE, null, null, groupToken);
        }

        static Parsed query(String groupToken) {
            return new Parsed(Type.QUERY, null, null, groupToken);
        }
    }
}
