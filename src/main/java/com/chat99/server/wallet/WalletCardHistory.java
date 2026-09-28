package com.chat99.server.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Positive evidence only: an absent/expired/deleted history is not proof of non-delivery. */
final class WalletCardHistory {
    record Page(boolean found, String key, Long seq, String nextCursor) {}

    @SuppressWarnings("unchecked")
    static Page inspect(WalletCardOutbox order, Map<String, Object> response, ObjectMapper json) {
        Object list = response.get(order.isGroupMessage() ? "RspMsgList" : "MsgList");
        if (!(list instanceof List<?> messages)) throw new IllegalArgumentException("Missing history list");
        long minSeq = Long.MAX_VALUE;
        long minTime = Long.MAX_VALUE;
        for (Object item : messages) {
            if (!(item instanceof Map<?, ?>)) throw new IllegalArgumentException("Invalid history message");
            Map<String, Object> msg = (Map<String, Object>) item;
            if (msg.get("MsgSeq") instanceof Number n && n.longValue() > 0) minSeq = Math.min(minSeq, n.longValue());
            if (msg.get("MsgTimeStamp") instanceof Number n) minTime = Math.min(minTime, n.longValue());
            if (!order.getSenderId().equals(msg.get("From_Account"))) continue;
            if (!order.isGroupMessage() && !order.getTargetId().equals(msg.get("To_Account"))) continue;
            if (!(msg.get("MsgRandom") instanceof Number n) || n.intValue() != order.getImRandom()) continue;
            if (msg.get("IsPlaceMsg") instanceof Number place && place.intValue() != 0) continue;
            for (var card : WalletOrderCardImSupport.extractCards(msg, json)) {
                String prefix = WalletOrderCardImSupport.TYPE_TRANSFER.equals(card.customType()) ? "transfer:" : "rp:";
                if (!order.getId().equals(prefix + card.orderIdRaw())) continue;
                String key = msg.get("MsgKey") == null ? null : msg.get("MsgKey").toString();
                Long seq = msg.get("MsgSeq") instanceof Number s ? s.longValue() : null;
                if (order.isGroupMessage() ? seq != null && seq > 0 : key != null && !key.isBlank())
                    return new Page(true, key, seq, null);
            }
        }
        String next = null;
        if (order.isGroupMessage()) {
            if (minSeq > 1 && minSeq != Long.MAX_VALUE
                && minTime >= order.getFirstAttemptAt().getEpochSecond() - 60)
                next = Long.toString(minSeq - 1);
        } else if (response.get("Complete") instanceof Number complete && complete.intValue() == 0) {
            Object key = response.get("LastMsgKey");
            if (key == null || key.toString().isBlank() || !(response.get("LastMsgTime") instanceof Number))
                throw new IllegalArgumentException("Missing history continuation");
            next = ((Number) response.get("LastMsgTime")).longValue() + "|" + key;
        }
        if (next != null && next.equals(order.getReconcileCursor()))
            throw new IllegalArgumentException("History cursor did not advance");
        return new Page(false, null, null, next);
    }
}
