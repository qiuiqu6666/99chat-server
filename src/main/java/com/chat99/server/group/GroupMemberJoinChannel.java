package com.chat99.server.group;

/**
 * 成员入群渠道（新数据）；历史行为 null。
 * 客户端：{@code invite} 显示邀请人；{@code group_id} 文案「通过群ID加入」。
 */
public final class GroupMemberJoinChannel {
    public static final String INVITE = "invite";
    public static final String GROUP_ID = "group_id";

    private GroupMemberJoinChannel() {}
}
