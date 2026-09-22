package com.chat99.server.integration;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.messagearchive.ChatMessageTableRouter;
import com.chat99.server.messagearchive.ChatMessageWriteRepository;
import com.chat99.server.messagearchive.ImMessageRecallParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImExternalMessageRecallService {

    private static final int MAX_GROUP_SEQ_PER_REQUEST = 10;
    private static final int MONTHS_TO_SEARCH = 2;

    private final ImAdminClient imAdminClient;
    private final ObjectProvider<ChatMessageWriteRepository> writeRepository;
    private final ObjectProvider<ChatMessageTableRouter> tableRouter;

    public ImExternalMessageRecallService(ImAdminClient imAdminClient,
                                          ObjectProvider<ChatMessageWriteRepository> writeRepository,
                                          ObjectProvider<ChatMessageTableRouter> tableRouter) {
        this.imAdminClient = imAdminClient;
        this.writeRepository = writeRepository;
        this.tableRouter = tableRouter;
    }

    public ExternalRecallResult recallC2c(String fromAccount,
                                            String toAccount,
                                            String msgKey,
                                            boolean syncArchive) {
        validateC2cInput(fromAccount, toAccount, msgKey);
        imAdminClient.adminRecallC2cMessage(fromAccount.trim(), toAccount.trim(), msgKey.trim());
        List<String> msgKeys = List.of(msgKey.trim());
        int archiveUpdated = syncArchive ? syncArchive(msgKeys) : 0;
        return new ExternalRecallResult(
            "c2c",
            true,
            0,
            msgKeys,
            List.of(),
            archiveUpdated);
    }

    public ExternalRecallResult recallGroup(String groupId,
                                            List<Long> msgSeqList,
                                            String reason,
                                            boolean syncArchive) {
        String gid = validateGroupInput(groupId, msgSeqList);
        Map<String, Object> imResp = imAdminClient.adminRecallGroupMessages(gid, msgSeqList, reason);
        List<GroupSeqResult> groupResults = parseGroupResults(imResp);
        List<String> recalledMsgKeys = new ArrayList<>();
        for (GroupSeqResult item : groupResults) {
            if (item.retCode() == 0) {
                recalledMsgKeys.add(ImMessageRecallParser.groupMsgKey(gid, item.msgSeq()));
            }
        }
        if (recalledMsgKeys.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "IM_RECALL_FAILED");
        }
        int archiveUpdated = syncArchive ? syncArchive(recalledMsgKeys) : 0;
        return new ExternalRecallResult(
            "group",
            true,
            0,
            recalledMsgKeys,
            groupResults,
            archiveUpdated);
    }

    private int syncArchive(List<String> msgKeys) {
        ChatMessageWriteRepository repo = writeRepository.getIfAvailable();
        ChatMessageTableRouter router = tableRouter.getIfAvailable();
        if (repo == null || router == null || msgKeys.isEmpty()) {
            return 0;
        }
        List<String> tables = router.physicalTablesAround(Instant.now(), MONTHS_TO_SEARCH);
        int updated = 0;
        for (String table : tables) {
            updated += repo.batchMarkRevoked(table, msgKeys);
        }
        return updated;
    }

    private static void validateC2cInput(String fromAccount, String toAccount, String msgKey) {
        if (fromAccount == null || fromAccount.isBlank()
            || toAccount == null || toAccount.isBlank()
            || msgKey == null || msgKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    private static String validateGroupInput(String groupId, List<Long> msgSeqList) {
        if (groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (msgSeqList == null || msgSeqList.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (msgSeqList.size() > MAX_GROUP_SEQ_PER_REQUEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MSG_SEQ_LIMIT_EXCEEDED");
        }
        for (Long seq : msgSeqList) {
            if (seq == null || seq <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
        }
        return groupId.trim();
    }

    @SuppressWarnings("unchecked")
    private static List<GroupSeqResult> parseGroupResults(Map<String, Object> imResp) {
        Object raw = imResp.get("RecallRetList");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<GroupSeqResult> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            long msgSeq = longVal(entry.get("MsgSeq"));
            int retCode = (int) longVal(entry.get("RetCode"));
            if (msgSeq > 0) {
                out.add(new GroupSeqResult(msgSeq, retCode));
            }
        }
        return out;
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

    public record ExternalRecallResult(
        String chatType,
        boolean imSuccess,
        int imErrorCode,
        List<String> recalledMsgKeys,
        List<GroupSeqResult> groupResults,
        int archiveUpdated) {}

    public record GroupSeqResult(long msgSeq, int retCode) {}

    public record RecallRequest(
        String chatType,
        String fromAccount,
        String toAccount,
        String msgKey,
        String groupId,
        List<Long> msgSeqList,
        String reason,
        Boolean syncArchive) {

        public boolean syncArchiveOrDefault() {
            return syncArchive == null || syncArchive;
        }
    }
}
