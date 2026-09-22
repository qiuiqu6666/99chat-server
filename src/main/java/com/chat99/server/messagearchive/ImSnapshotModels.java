package com.chat99.server.messagearchive;

import java.util.List;
import java.util.Map;

/** IM 登录快照响应模型。故意不含 unread 字段。 */
public final class ImSnapshotModels {

    private ImSnapshotModels() {}

    public record SnapshotResponse(
        List<ConversationSummary> conversations,
        List<PreloadBucket> preload,
        boolean degraded) {

        public static SnapshotResponse emptyDegraded() {
            return new SnapshotResponse(List.of(), List.of(), true);
        }

        public static SnapshotResponse of(
            List<ConversationSummary> conversations,
            List<PreloadBucket> preload,
            boolean degraded) {
            return new SnapshotResponse(
                conversations == null ? List.of() : List.copyOf(conversations),
                preload == null ? List.of() : List.copyOf(preload),
                degraded);
        }
    }

    public record ConversationSummary(
        String conversationId,
        String type,
        String chatType,
        String peerId,
        Long lastSeq,
        SnapshotMessage lastMessage,
        boolean pinned,
        Long pinnedAt) {}

    public record PreloadBucket(
        String conversationId,
        List<SnapshotMessage> messages) {

        public PreloadBucket {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public record SnapshotMessage(
        String msgId,
        String msgKey,
        Long seq,
        String fromUserId,
        String sender,
        long time,
        String type,
        String text,
        List<Map<String, Object>> msgBody,
        int status) {}
}
