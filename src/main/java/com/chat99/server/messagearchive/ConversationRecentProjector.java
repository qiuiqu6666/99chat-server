package com.chat99.server.messagearchive;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ConversationRecentProjector {

    private static final Logger log = LoggerFactory.getLogger(ConversationRecentProjector.class);

    private final ConversationRecentRepository repository;

    public ConversationRecentProjector(ConversationRecentRepository repository) {
        this.repository = repository;
    }

    /**
     * 归档写入成功后调用。投影失败不得抛出影响归档 ack。
     */
    public void apply(List<ImMessageArchiveEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        int ok = 0;
        for (ImMessageArchiveEvent event : events) {
            if (event == null) {
                continue;
            }
            try {
                if (ImMessageArchiveParser.CHAT_TYPE_GROUP.equals(event.chatType())) {
                    String groupId = event.groupId();
                    if (groupId == null || groupId.isBlank()) {
                        continue;
                    }
                    repository.upsertGroup(
                        groupId.trim(),
                        event.msgTimeMs(),
                        event.msgKey(),
                        event.msgSeq(),
                        event.fromAccount(),
                        event.elemType(),
                        event.previewText());
                    ok++;
                } else if (ImMessageArchiveParser.CHAT_TYPE_C2C.equals(event.chatType())) {
                    String from = event.fromAccount();
                    String peer = event.peerAccount();
                    if (from == null || from.isBlank() || peer == null || peer.isBlank() || from.equals(peer)) {
                        continue;
                    }
                    String fromTrim = from.trim();
                    String peerTrim = peer.trim();
                    repository.upsertC2c(
                        fromTrim, peerTrim, event.msgTimeMs(), event.msgKey(),
                        event.msgSeq(), fromTrim, event.elemType(), event.previewText());
                    repository.upsertC2c(
                        peerTrim, fromTrim, event.msgTimeMs(), event.msgKey(),
                        event.msgSeq(), fromTrim, event.elemType(), event.previewText());
                    ok++;
                }
            } catch (Exception e) {
                log.warn("conversation recent project skip event msgKey={} err={}",
                    event.msgKey(), e.toString());
            }
        }
        if (ok > 0) {
            log.debug("conversation recent projected eventsOk={} total={}", ok, events.size());
        }
    }
}
