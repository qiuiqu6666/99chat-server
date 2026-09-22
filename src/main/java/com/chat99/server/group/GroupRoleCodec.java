package com.chat99.server.group;

public final class GroupRoleCodec {

    public static final int MEMBER = 200;
    public static final int ADMIN = 300;
    public static final int OWNER = 400;

    private GroupRoleCodec() {
    }

    public static Integer fromImRole(String imRole) {
        if (imRole == null || imRole.isBlank()) {
            return null;
        }
        return switch (imRole.trim()) {
            case "Owner" -> OWNER;
            case "Admin" -> ADMIN;
            case "Member" -> MEMBER;
            default -> null;
        };
    }

    public static int fromImRoleOrDefault(String imRole) {
        Integer role = fromImRole(imRole);
        return role == null ? MEMBER : role;
    }

    /** Client-facing display identity for group member lists. */
    public static String roleName(int role) {
        return switch (role) {
            case OWNER -> "owner";
            case ADMIN -> "admin";
            default -> "member";
        };
    }

    public static String toImRole(int role) {
        return switch (role) {
            case OWNER -> "Owner";
            case ADMIN -> "Admin";
            default -> "Member";
        };
    }

    public static boolean isOwnerImRole(String imRole) {
        return "Owner".equals(imRole);
    }

    public static boolean isAdminImRole(String imRole) {
        return "Admin".equals(imRole);
    }
}
