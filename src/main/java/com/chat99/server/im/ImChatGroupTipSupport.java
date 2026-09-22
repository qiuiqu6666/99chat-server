package com.chat99.server.im;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ImChatGroupTipSupport {

    static final int TIP_JOIN = 1;
    static final int TIP_INVITE = 2;
    static final int TIP_QUIT = 3;
    static final int TIP_KICKED = 4;
    static final int TIP_SET_ADMIN = 5;
    static final int TIP_CANCEL_ADMIN = 6;
    static final int TIP_GROUP_INFO_CHANGE = 7;
    static final int TIP_MEMBER_INFO_CHANGE = 8;

    private static final Set<Integer> SILENT_INFO_FLAGS = Set.of(1, 2, 3, 4, 8);
    private static final int INFO_FLAG_OWNER = 5;

    @FunctionalInterface
    interface DisplayNameLookup {
        String resolve(String userId, String inlineName);
    }

    private ImChatGroupTipSupport() {}

    static boolean isSilentGroupTip(Map<?, ?> content) {
        if (content == null) {
            return false;
        }
        int tipType = tipType(content);
        return switch (tipType) {
            case TIP_SET_ADMIN, TIP_CANCEL_ADMIN -> true;
            case TIP_GROUP_INFO_CHANGE -> isSilentGroupInfoChange(content);
            default -> false;
        };
    }

    static String summarize(Map<?, ?> content, DisplayNameLookup lookup) {
        if (content == null) {
            return "群聊有更新";
        }
        int tipType = tipType(content);
        String opId = opUserId(content);
        String opName = lookup.resolve(opId, opInlineName(content));
        List<String> memberIds = memberUserIds(content);
        String firstMemberId = memberIds.isEmpty() ? null : memberIds.get(0);
        String firstMemberName = lookup.resolve(firstMemberId, memberInlineName(content, 0));

        return switch (tipType) {
            case TIP_JOIN -> firstMemberName + "加入了群组";
            case TIP_INVITE -> formatInvite(opName, memberIds, content, lookup);
            case TIP_QUIT -> firstMemberName + "退出了群组";
            case TIP_KICKED -> formatKicked(opName, firstMemberName);
            case TIP_MEMBER_INFO_CHANGE -> formatMemberMute(content, memberIds, lookup);
            case TIP_GROUP_INFO_CHANGE -> formatGroupInfoChange(content, opName);
            default -> "群聊有更新";
        };
    }

    private static boolean isSilentGroupInfoChange(Map<?, ?> content) {
        List<Map<?, ?>> changes = groupChangeList(content);
        if (changes.isEmpty()) {
            return true;
        }
        for (Map<?, ?> change : changes) {
            int flag = changeFlag(change);
            if (!SILENT_INFO_FLAGS.contains(flag)) {
                return false;
            }
        }
        return true;
    }

    private static String formatInvite(String opName, List<String> memberIds,
                                       Map<?, ?> content, DisplayNameLookup lookup) {
        if (memberIds.isEmpty()) {
            return opName + "邀请成员加入群组";
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < memberIds.size(); i++) {
            String id = memberIds.get(i);
            names.add(lookup.resolve(id, memberInlineName(content, i)));
        }
        String membersLabel;
        if (names.size() <= 3) {
            membersLabel = String.join("、", names);
        } else {
            membersLabel = names.get(0) + "、" + names.get(1) + "等" + names.size() + "人";
        }
        return opName + "邀请" + membersLabel + "加入群组";
    }

    private static String formatKicked(String opName, String memberName) {
        if (opName == null || opName.isBlank() || opName.equals(memberName)) {
            return memberName + "被移出群组";
        }
        return opName + "将" + memberName + "移出群组";
    }

    private static String formatMemberMute(Map<?, ?> content, List<String> memberIds,
                                           DisplayNameLookup lookup) {
        List<Map<?, ?>> changes = memberChangeList(content);
        if (!changes.isEmpty()) {
            Map<?, ?> first = changes.get(0);
            String memberId = str(first.get("Member_Account"));
            if (memberId == null) {
                memberId = str(first.get("Identifier"));
            }
            String name = lookup.resolve(memberId, null);
            long shutup = longVal(first.get("ShutUpTime"));
            if (shutup == 0L) {
                shutup = longVal(first.get("ShutupTime"));
            }
            if (shutup == 0L) {
                shutup = longVal(first.get("shutup_time"));
            }
            return shutup > 0L ? name + "被禁言" : name + "被解除禁言";
        }
        if (!memberIds.isEmpty()) {
            String name = lookup.resolve(memberIds.get(0), null);
            return name + "被禁言";
        }
        return "群成员状态有变更";
    }

    private static String formatGroupInfoChange(Map<?, ?> content, String opName) {
        List<Map<?, ?>> changes = groupChangeList(content);
        for (Map<?, ?> change : changes) {
            if (changeFlag(change) == INFO_FLAG_OWNER) {
                return opName + "成为了群主";
            }
        }
        return opName + "修改了群资料";
    }

    private static int tipType(Map<?, ?> content) {
        Object raw = firstRaw(content, "TipType", "GroupTipType", "tips_type");
        return intVal(raw);
    }

    private static String opUserId(Map<?, ?> content) {
        String direct = str(firstRaw(content, "OpMember_Account", "Operator_Account", "OpUser", "opUser"));
        if (direct != null) {
            return direct;
        }
        Object opMember = content.get("OpMember");
        if (opMember instanceof Map<?, ?> map) {
            return str(map.get("Member_Account"));
        }
        return null;
    }

    private static String opInlineName(Map<?, ?> content) {
        Object opMember = content.get("OpMember");
        if (opMember instanceof Map<?, ?> map) {
            String nameCard = str(map.get("NameCard"));
            if (nameCard != null) {
                return nameCard;
            }
        }
        Object opInfo = content.get("OpGroupMemberInfo");
        if (opInfo instanceof Map<?, ?> map) {
            return str(map.get("NameCard"));
        }
        Object userInfo = content.get("OpUserInfo");
        if (userInfo instanceof Map<?, ?> map) {
            return firstNonBlank(str(map.get("Nick")), str(map.get("nick")));
        }
        return null;
    }

    private static List<String> memberUserIds(Map<?, ?> content) {
        List<String> ids = new ArrayList<>();
        Object memberList = firstRaw(content, "MemberList", "MemberNumList");
        if (memberList instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String id = str(map.get("Member_Account"));
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            Object userArray = content.get("UserArray");
            if (userArray instanceof List<?> list) {
                for (Object item : list) {
                    String id = str(item);
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids;
    }

    private static String memberInlineName(Map<?, ?> content, int index) {
        Object memberList = content.get("MemberList");
        if (memberList instanceof List<?> list && index < list.size()) {
            Object item = list.get(index);
            if (item instanceof Map<?, ?> map) {
                String nameCard = str(map.get("NameCard"));
                if (nameCard != null) {
                    return nameCard;
                }
            }
        }
        return null;
    }

    private static List<Map<?, ?>> groupChangeList(Map<?, ?> content) {
        return mapList(firstRaw(content, "GroupChangeInfoList", "GroupChangeInfoArray"));
    }

    private static List<Map<?, ?>> memberChangeList(Map<?, ?> content) {
        return mapList(firstRaw(content, "MemberChangeInfoList", "MemberChangeInfoArray"));
    }

    private static int changeFlag(Map<?, ?> change) {
        Object raw = firstRaw(change, "Type", "InfoFlag", "info_flag");
        return intVal(raw);
    }

    private static List<Map<?, ?>> mapList(Object raw) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(map);
            }
        }
        return out;
    }

    private static Object firstRaw(Map<?, ?> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static int intVal(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static long longVal(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
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
