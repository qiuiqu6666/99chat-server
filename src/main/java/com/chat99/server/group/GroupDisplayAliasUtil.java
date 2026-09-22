package com.chat99.server.group;

public final class GroupDisplayAliasUtil {

    private GroupDisplayAliasUtil() {
    }

    public static String compute(String groupType, String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return "";
        }
        if (groupType == null || !"Community".equalsIgnoreCase(groupType.trim())) {
            return "";
        }
        int hashIdx = groupId.indexOf('#');
        if (hashIdx < 0 || hashIdx >= groupId.length() - 1) {
            return "";
        }
        String suffix = groupId.substring(hashIdx + 1).trim();
        if (suffix.startsWith("_")) {
            suffix = suffix.substring(1);
        }
        if (suffix.isEmpty()) {
            return "";
        }
        return "@" + suffix;
    }
}
