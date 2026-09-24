package com.chat99.server.im.restqueue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ImRestJob(
    String jobId,
    Type type,
    String groupId,
    String userId,
    List<String> groupIds,
    int attempt,
    long enqueuedAt,
    String reason,
    String peerUserId,
    String remark,
    List<String> memberUserIds,
    String stringValue
) {
    public enum Type {
        REFRESH_ROLE,
        HYDRATE_GROUP,
        SYNC_USER_JOINED,
        VERIFY_GROUP_EXISTS,
        FRIEND_ADD_BOTH,
        FRIEND_DELETE_BOTH,
        FRIEND_REMARK_UPDATE,
        GROUP_MODIFY_BASE_INFO,
        GROUP_MODIFY_FACE_URL,
        GROUP_MODIFY_JOIN_OPTIONS,
        GROUP_ADD_MEMBERS,
        GROUP_DELETE_MEMBERS,
        GROUP_DESTROY,
        RECONCILE_GROUP_MEMBERS,
        RECONCILE_GROUP_USERS
    }

    public ImRestJob withAttempt(int nextAttempt) {
        return new ImRestJob(
            jobId, type, groupId, userId, groupIds, nextAttempt, enqueuedAt, reason,
            peerUserId, remark, memberUserIds, stringValue);
    }
}
