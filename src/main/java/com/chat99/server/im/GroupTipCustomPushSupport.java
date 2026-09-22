package com.chat99.server.im;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自建 Push 对客户端 Custom {@code businessID=group_tip} 的正文组装。
 * 优先 {@code previewAbstract}，否则按 action 与客户端灰字同语义拼装；禁止用 businessID 当正文。
 */
final class GroupTipCustomPushSupport {

    static final String BUSINESS_ID = "group_tip";
    private static final String FALLBACK = "群提示";

    private static final Set<String> KNOWN_ACTIONS = Set.of(
        "member_added",
        "member_removed",
        "member_left",
        "member_muted",
        "member_unmuted",
        "group_mute_all_on",
        "group_mute_all_off",
        "member_set_admin",
        "member_cancel_admin",
        "group_name_changed",
        "group_avatar_changed",
        "group_notice_changed",
        "owner_changed",
        "group_apply_join_option_changed",
        "group_invite_join_option_changed",
        "group_qr_join_enabled",
        "group_qr_join_disabled",
        "group_alias_join_enabled",
        "group_alias_join_disabled",
        "group_privacy_enabled",
        "group_privacy_disabled"
    );

    private GroupTipCustomPushSupport() {}

    static boolean isGroupTip(Map<String, Object> data) {
        return GroupTipImSupport.isGroupTipData(data);
    }

    static String pushBody(Map<String, Object> data) {
        if (data == null) {
            return FALLBACK;
        }
        String preview = str(data.get("previewAbstract"));
        if (preview != null && !preview.isBlank()) {
            return preview.trim();
        }
        String composed = compose(data);
        if (composed != null && !composed.isBlank()) {
            return composed;
        }
        return FALLBACK;
    }

    static String compose(Map<String, Object> data) {
        String action = normalizeAction(str(data.get("action")));
        if (action == null || !KNOWN_ACTIONS.contains(action)) {
            return FALLBACK;
        }
        String op = displayName(str(data.get("opUserName")), str(data.get("opUserId")), "成员");
        String members = joinMembers(data);
        return switch (action) {
            case "member_added" -> op + "邀请" + membersOrFallback(members) + "加入群组";
            case "member_removed" -> op + "将" + membersOrFallback(members) + "踢出群组";
            case "member_left" -> leaver(data, op, members) + "退出群聊";
            case "member_muted" -> op + "将" + membersOrFallback(members) + "禁言";
            case "member_unmuted" -> op + "解除了" + membersOrFallback(members) + "的禁言";
            case "group_mute_all_on" -> op + "开启了全员禁言";
            case "group_mute_all_off" -> op + "关闭了全员禁言";
            case "member_set_admin" -> op + "将" + membersOrFallback(members) + "设置为管理员";
            case "member_cancel_admin" -> op + "将" + membersOrFallback(members) + "取消管理员";
            case "group_name_changed" -> op + "修改了群名称";
            case "group_avatar_changed" -> op + "修改了群头像";
            case "group_notice_changed" -> op + "修改了群公告";
            case "owner_changed" -> op + "将群主转让给" + membersOrFallback(members);
            case "group_apply_join_option_changed" ->
                op + "将申请加群方式修改为" + joinOptionLabel(detailValue(data, "applyJoinOption"));
            case "group_invite_join_option_changed" ->
                op + "将邀请好友方式修改为" + joinOptionLabel(detailValue(data, "inviteJoinOption"));
            case "group_qr_join_enabled" -> op + "开启了二维码加群";
            case "group_qr_join_disabled" -> op + "关闭了二维码加群";
            case "group_alias_join_enabled" -> op + "开启了群别名加群";
            case "group_alias_join_disabled" -> op + "关闭了群别名加群";
            case "group_privacy_enabled" -> op + "开启了群成员隐私保护";
            case "group_privacy_disabled" -> op + "关闭了群成员隐私保护";
            default -> FALLBACK;
        };
    }

    private static String leaver(Map<String, Object> data, String op, String members) {
        if (members != null && !members.isBlank()) {
            return members;
        }
        List<String> ids = stringList(data.get("memberUserIds"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        return op;
    }

    private static String membersOrFallback(String members) {
        return members != null && !members.isBlank() ? members : "成员";
    }

    private static String joinMembers(Map<String, Object> data) {
        List<String> names = stringList(data.get("memberNames"));
        if (names.isEmpty()) {
            names = stringList(data.get("memberUserIds"));
        }
        if (names.isEmpty()) {
            return "";
        }
        return String.join("、", names);
    }

    private static String joinOptionLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未知";
        }
        String v = raw.trim();
        String lower = v.toLowerCase(Locale.ROOT);
        if (containsAny(v, "自由", "自动") || lower.contains("free") || lower.contains("auto")) {
            return "自动审批";
        }
        if (containsAny(v, "审批", "许可", "需") || lower.contains("need") || lower.contains("permission")
            || lower.contains("admin")) {
            return "管理员审批";
        }
        if (containsAny(v, "禁止", "关闭", "不可") || lower.contains("disable") || lower.contains("forbid")
            || lower.contains("deny")) {
            return "禁止";
        }
        return v;
    }

    private static boolean containsAny(String text, String... parts) {
        for (String part : parts) {
            if (text.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static String detailValue(Map<String, Object> data, String key) {
        Object detail = data.get("detail");
        if (detail instanceof Map<?, ?> map) {
            return str(map.get(key));
        }
        return str(data.get(key));
    }

    private static String displayName(String name, String userId, String fallback) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        if (userId != null && !userId.isBlank()) {
            return userId.trim();
        }
        return fallback;
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String s = str(item);
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static String normalizeAction(String action) {
        return action == null ? null : action.trim().toLowerCase(Locale.ROOT);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
