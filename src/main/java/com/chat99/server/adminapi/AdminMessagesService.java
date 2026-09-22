package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminMessagesService {

    private final AdminUserManagementService users;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;

    public AdminMessagesService(AdminUserManagementService users, ImAdminClient imAdmin,
                                ImUserIdService imUserIdService) {
        this.users = users;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
    }

    public C2cMessagesResponse listC2c(String userA, String userB, String keyword,
                                       int page, int pageSize, String lastMsgKey) {
        validateUserUid(userA);
        validateUserUid(userB);
        users.requireUser(userA);
        users.requireUser(userB);
        int size = Math.min(Math.max(pageSize, 1), 100);
        List<ImAdminClient.RoamMessage> raw = imAdmin.adminGetRoamMessagesMerged(
            imUserIdService.toIm(userA), imUserIdService.toIm(userB), size, lastMsgKey);
        List<MessageItem> items = raw.stream()
            .map(this::toItem)
            .filter(m -> matchesKeyword(m, keyword))
            .toList();
        String nextKey = raw.isEmpty() ? null : raw.get(raw.size() - 1).msgKey();
        return new C2cMessagesResponse(items, page, size, nextKey, items.size() >= size);
    }

    public GroupMessagesResponse listGroup(String groupId, String keyword,
                                           int page, int pageSize, Long reqMsgSeq) {
        if (groupId == null || !groupId.matches("^[A-Za-z0-9@#_-]{1,32}$")) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid g_id");
        }
        int size = Math.min(Math.max(pageSize, 1), 100);
        List<ImAdminClient.RoamMessage> raw = imAdmin.getGroupMessages(groupId, size, reqMsgSeq);
        List<MessageItem> items = raw.stream()
            .map(this::toItem)
            .filter(m -> matchesKeyword(m, keyword))
            .toList();
        Long nextSeq = raw.isEmpty() ? null : raw.get(raw.size() - 1).msgTimeSec();
        return new GroupMessagesResponse(items, page, size, nextSeq, items.size() >= size);
    }

    /** 平台 10 位小写 ID，以及 99Messenger / 99Chat 等系统号。 */
    private static void validateUserUid(String uid) {
        if (uid == null || (uid = uid.trim()).isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid user uid");
        }
        if (uid.length() > 32 || !uid.matches("^[A-Za-z0-9]+$")) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid user uid");
        }
    }

    private MessageItem toItem(ImAdminClient.RoamMessage m) {
        return new MessageItem(
            m.fromAccount() == null || m.fromAccount().isBlank()
                ? m.fromAccount()
                : imUserIdService.toBusinessForDisplay(m.fromAccount()),
            m.toAccount() == null || m.toAccount().isBlank()
                ? m.toAccount()
                : imUserIdService.toBusinessForDisplay(m.toAccount()),
            m.msgType(),
            m.textPreview(),
            m.msgTimeSec(),
            m.msgKey());
    }

    private static boolean matchesKeyword(MessageItem m, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.toLowerCase();
        return (m.textPreview() != null && m.textPreview().toLowerCase().contains(kw))
            || (m.fromAccount() != null && m.fromAccount().toLowerCase().contains(kw));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MessageItem(
        String fromAccount,
        String toAccount,
        String msgType,
        String textPreview,
        long msgTime,
        String msgKey) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record C2cMessagesResponse(
        List<MessageItem> items,
        int page,
        int pageSize,
        String lastMsgKey,
        boolean hasMore) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupMessagesResponse(
        List<MessageItem> items,
        int page,
        int pageSize,
        Long nextReqMsgSeq,
        boolean hasMore) {}
}
