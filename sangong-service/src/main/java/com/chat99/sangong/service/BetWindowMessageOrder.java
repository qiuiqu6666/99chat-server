package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongImMessage;
import com.chat99.sangong.domain.SangongRound;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 下注窗口内 IM 消息顺序：msg_seq 升序（无 seq 置后），同 seq 按 id 升序。 */
@Service
public class BetWindowMessageOrder {
    private final com.chat99.sangong.repository.ImMessageRepository messages;

    public BetWindowMessageOrder(com.chat99.sangong.repository.ImMessageRepository messages) {
        this.messages = messages;
    }

    public int compare(SangongImMessage a, SangongImMessage b) {
        Long aSeq = a.getMsgSeq();
        Long bSeq = b.getMsgSeq();
        if (aSeq != null && bSeq != null) {
            int c = aSeq.compareTo(bSeq);
            if (c != 0) return c;
        } else if (aSeq != null) {
            return -1;
        } else if (bSeq != null) {
            return 1;
        }
        return Long.compare(a.getId(), b.getId());
    }

    /** 截止消息（含）之前的消息 id，按窗口顺序。 */
    public List<Long> idsAtOrBefore(SangongRound round, long cutoffMessageId, List<Long> excludeMessageIds) {
        SangongImMessage cutoff = messages.findById(cutoffMessageId).orElse(null);
        if (cutoff == null || cutoff.getRoundId() == null || cutoff.getRoundId() != round.getId()) {
            throw new RuntimeException("截止消息不存在或不属于本局");
        }
        List<SangongImMessage> ordered = messages.listByRoundOrdered(round.getId());
        List<Long> ids = new ArrayList<>();
        boolean found = false;
        for (SangongImMessage message : ordered) {
            ids.add(message.getId());
            if (message.getId() == cutoffMessageId) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new RuntimeException("截止消息不存在或不属于本局");
        }
        if (excludeMessageIds == null || excludeMessageIds.isEmpty()) {
            return ids;
        }
        Set<Long> exclude = new HashSet<>(excludeMessageIds);
        return ids.stream().filter(id -> !exclude.contains(id)).toList();
    }

    public void validateExcludeMessageIds(SangongRound round, long cutoffMessageId, List<Long> excludeMessageIds) {
        if (excludeMessageIds == null || excludeMessageIds.isEmpty()) {
            return;
        }
        Set<Long> included = new HashSet<>(idsAtOrBefore(round, cutoffMessageId, List.of()));
        for (long excludeId : excludeMessageIds) {
            SangongImMessage message = messages.findById(excludeId).orElse(null);
            if (message == null || message.getRoundId() == null || message.getRoundId() != round.getId()) {
                throw new RuntimeException("排除消息不存在或不属于本局");
            }
            if (!included.contains(excludeId)) {
                throw new RuntimeException("排除消息不在截止范围内");
            }
        }
    }

    public List<Long> normalizeExcludeMessageIds(Object single, Object many) {
        Set<Long> ids = new LinkedHashSet<>();
        if (many instanceof List<?> list) {
            for (Object o : list) {
                long id = toLong(o);
                if (id > 0) ids.add(id);
            }
        }
        if (single != null) {
            long id = toLong(single);
            if (id > 0) ids.add(id);
        }
        return new ArrayList<>(ids);
    }

    public SangongImMessage resolveCutoffMessage(SangongRound round, Long untilMessageId, Long untilMsgSeq) {
        if (untilMessageId != null) {
            SangongImMessage message = messages.findById(untilMessageId).orElse(null);
            if (message == null || message.getRoundId() == null || message.getRoundId() != round.getId()) {
                throw new RuntimeException("截止消息不存在或不属于本局");
            }
            return message;
        }
        if (untilMsgSeq != null) {
            return messages.findByRoundAndSeq(round.getId(), untilMsgSeq)
                .orElseThrow(() -> new RuntimeException("截止 MsgSeq 不存在于本局"));
        }
        SangongImMessage withSeq = messages.findLatestWithSeq(round.getId()).orElse(null);
        if (withSeq != null) {
            return withSeq;
        }
        return messages.findLatestById(round.getId())
            .orElseThrow(() -> new RuntimeException("本局尚无 IM 消息，请指定 untilMessageId"));
    }

    public SangongImMessage latestRoundMessage(SangongRound round) {
        try {
            return resolveCutoffMessage(round, null, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
