package com.chat99.server.sync;

/**
 * 业务数据双轨同步域。每个域各自独立 revision（不跨域唯一），
 * 见 docs/sync-protocol.md。
 */
public enum SyncDomain {
    /** 好友通讯录（user_friend / friend_contact_change）。 */
    CONTACTS("contacts"),
    /** 群展示（group_profile / group_change_event）。 */
    GROUPS("groups"),
    /** 群成员（group_member / group_member_change）；需额外 groupId。 */
    GROUP_MEMBERS("groupMembers"),
    /** 群通知收件箱（group_notice_inbox_change）。 */
    GROUP_NOTICES("groupNotices");

    private final String path;

    SyncDomain(String path) {
        this.path = path;
    }

    /** URL 路径段。 */
    public String path() {
        return path;
    }

    /** 路径段 → enum；非法返回 null。 */
    public static SyncDomain fromPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = path.trim();
        for (SyncDomain d : values()) {
            if (d.path.equals(p)) {
                return d;
            }
        }
        return null;
    }
}
