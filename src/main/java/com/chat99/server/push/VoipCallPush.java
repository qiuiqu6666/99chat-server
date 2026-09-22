package com.chat99.server.push;

/**
 * VoIP / PushKit 来电与终态 payload。
 * {@code action}: {@code invite}（默认来电）| {@code answered_elsewhere} | {@code cancel} | {@code reject} | {@code hangup}
 */
public record VoipCallPush(
    String inviteId,
    String callerId,
    String calleeId,
    String mediaType,
    String roomId,
    String callerName,
    String callerAvatarUrl,
    String type,
    String action) {

    public VoipCallPush(String inviteId, String callerId, String calleeId, String mediaType, String roomId) {
        this(inviteId, callerId, calleeId, mediaType, roomId, null, null, "av_call", "invite");
    }

    public VoipCallPush(String inviteId, String callerId, String calleeId, String mediaType, String roomId,
                        String callerName, String callerAvatarUrl) {
        this(inviteId, callerId, calleeId, mediaType, roomId, callerName, callerAvatarUrl, "av_call", "invite");
    }

    public VoipCallPush(String inviteId, String callerId, String calleeId, String mediaType, String roomId,
                        String callerName, String callerAvatarUrl, String type) {
        this(inviteId, callerId, calleeId, mediaType, roomId, callerName, callerAvatarUrl, type, "invite");
    }

    public static VoipCallPush of(String inviteId, String callerId, String calleeId, String mediaType,
                                  String roomId, String callerName, String callerAvatarUrl, String type) {
        return new VoipCallPush(inviteId, callerId, calleeId, mediaType, roomId, callerName, callerAvatarUrl, type, "invite");
    }

    public static VoipCallPush ended(String inviteId, String callerId, String calleeId, String mediaType,
                                     String roomId, String type, String action) {
        return new VoipCallPush(inviteId, callerId, calleeId, mediaType, roomId, null, null, type, action);
    }

    public VoipCallPush {
        mediaType = mediaType == null || mediaType.isBlank() ? "audio" : mediaType;
        type = type == null || type.isBlank() ? "av_call" : type;
        action = action == null || action.isBlank() ? "invite" : action.trim();
    }

    public boolean isTerminal() {
        return !"invite".equalsIgnoreCase(action);
    }
}
