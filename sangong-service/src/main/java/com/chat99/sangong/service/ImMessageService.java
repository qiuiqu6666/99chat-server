package com.chat99.sangong.service;

import com.chat99.sangong.common.InsufficientBalanceException;
import com.chat99.sangong.common.TimeFmt;
import com.chat99.sangong.domain.SangongImMessage;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.BetRepository;
import com.chat99.sangong.repository.ImMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * IM 回调处理：发言入库（幂等 group_id+msg_seq）、下注预录入、截止提交、撤回撤销。
 * 与 PHP ImMessageService 行为一致。
 */
@Service
public class ImMessageService {
    public record CallbackResult(boolean isTencent, int status, Map<String, Object> body) {}

    private static final Logger log = LoggerFactory.getLogger(ImMessageService.class);
    private static final List<String> PENDING_OUTCOMES = List.of(
        SangongImMessage.OUTCOME_PENDING_SUFFICIENT,
        SangongImMessage.OUTCOME_PENDING_INSUFFICIENT,
        SangongImMessage.OUTCOME_PENDING_REJECTED);

    private final BetService bets;
    private final UserService users;
    private final GameSettingsService settings;
    private final ImService im;
    private final RoundService rounds;
    private final BetTextParser betTextParser;
    private final BetWindowMessageOrder messageOrder;
    private final ImMessageRepository messages;
    private final BetRepository betRepo;
    private final RealtimeVersionStore realtime;
    private final ObjectMapper mapper = new ObjectMapper();

    public ImMessageService(BetService bets, UserService users, GameSettingsService settings,
                            ImService im, RoundService rounds, BetTextParser betTextParser,
                            BetWindowMessageOrder messageOrder, ImMessageRepository messages,
                            BetRepository betRepo, RealtimeVersionStore realtime) {
        this.bets = bets;
        this.users = users;
        this.settings = settings;
        this.im = im;
        this.rounds = rounds;
        this.betTextParser = betTextParser;
        this.messageOrder = messageOrder;
        this.messages = messages;
        this.betRepo = betRepo;
        this.realtime = realtime;
    }

    // ===== 发言回调 =====

    public CallbackResult handleSend(Map<String, Object> payload, boolean isTencent) {
        ParsedSend parsed = parseSendPayload(payload);
        if (parsed == null) {
            return wrap(isTencent, 200, body("ok", true, "ignored", true, "reason", "not_a_message"));
        }

        if (parsed.msgSeq() != null) {
            SangongImMessage existing = messages.findByGroupAndSeq(parsed.groupId(), parsed.msgSeq()).orElse(null);
            if (existing != null) {
                return buildSendResultFromMessage(existing, isTencent, true);
            }
        }

        SangongImMessage message = new SangongImMessage();
        message.setGroupId(parsed.groupId());
        message.setImUserId(parsed.imUserId());
        message.setNickname(parsed.nickname());
        message.setMsgSeq(parsed.msgSeq());
        message.setMsgId(parsed.msgId());
        message.setMsgTime(parsed.msgTime() != null ? parsed.msgTime() : Instant.now().getEpochSecond());
        message.setText(parsed.text());
        message.setCallbackCommand(parsed.command());
        message.setRawPayload(toJson(payload));
        message.setOutcome(SangongImMessage.OUTCOME_STORED);
        try {
            messages.insert(message);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // group_id+msg_seq 幂等：并发重复回调直接返回已有记录
            if (parsed.msgSeq() != null) {
                SangongImMessage existing = messages.findByGroupAndSeq(parsed.groupId(), parsed.msgSeq()).orElse(null);
                if (existing != null) {
                    return buildSendResultFromMessage(existing, isTencent, true);
                }
            }
            throw e;
        }

        SangongRound round = rounds.getCurrent();
        if (round != null) {
            message.setRoundId(round.getId());
            messages.updateRoundId(message.getId(), round.getId());
        }

        if (!isGameGroup(parsed.groupId())) {
            return finalizeIgnored(message, "not_game_group", isTencent);
        }
        String botUserId = settings.getImBotUserId();
        if (!botUserId.isEmpty() && parsed.imUserId().equals(botUserId)) {
            return finalizeIgnored(message, "bot_message", isTencent);
        }
        if (round == null || !round.isBetWindowOpen()) {
            return finalizeIgnored(message, "bet_window_closed", isTencent);
        }

        BetTextParser.Parsed parsedBet = betTextParser.parse(parsed.text());
        if (parsedBet == null) {
            return finalizeIgnored(message, "not_bet_format", isTencent);
        }

        ResolvedBet resolved = resolveBetForRound(round, parsedBet);
        if (resolved == null) {
            SangongUser user = users.findOrCreateByImUserId(parsed.imUserId(), parsed.nickname());
            String displayName = users.resolveNickname(user, parsed.nickname());
            String reason = betTextParser.resolveRejectReason(parsedBet, settings.getDoorCount(), round.getBankerDoor());
            String detail = outcomeDetailForRejectReason(reason);
            messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_PENDING_REJECTED, detail, null);
            im.notifyBetRejected(user, displayName, reason);
            realtime.touch();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("ok", false);
            b.put("pending", true);
            b.put("submitted", false);
            b.put("code", detail);
            b.put("messageId", message.getId());
            b.put("message", reason);
            return wrap(isTencent, 422, b);
        }

        SangongUser user = users.findOrCreateByImUserId(parsed.imUserId(), parsed.nickname());
        long pendingReserved = sumUserPendingSufficientAmount(round, parsed.imUserId());
        Map<String, Object> validation = bets.validateMultiBetCommand(user, resolved.doors(), resolved.amount(),
            true, pendingReserved);

        if (!Boolean.TRUE.equals(validation.get("ok"))) {
            String code = String.valueOf(validation.getOrDefault("code", "BET_REJECTED"));
            if ("INSUFFICIENT_BALANCE".equals(code)) {
                messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_PENDING_INSUFFICIENT,
                    "INSUFFICIENT_BALANCE", null);
            } else {
                messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_PENDING_REJECTED, code, null);
            }
            realtime.touch();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("ok", false);
            b.put("pending", true);
            b.put("submitted", false);
            b.put("code", code);
            b.put("messageId", message.getId());
            b.put("doors", resolved.doors());
            b.put("amount", resolved.amount());
            b.put("totalAmount", resolved.amount() * resolved.doors().size());
            b.put("balance", validation.get("balance"));
            b.put("message", validation.getOrDefault("message", "下注校验未通过"));
            return wrap(isTencent, 422, b);
        }

        messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_PENDING_SUFFICIENT, null, null);
        realtime.touch();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ok", true);
        b.put("pending", true);
        b.put("submitted", false);
        b.put("messageId", message.getId());
        b.put("doors", resolved.doors());
        b.put("amount", resolved.amount());
        b.put("totalAmount", resolved.amount() * resolved.doors().size());
        b.put("allIdle", resolved.allIdle());
        b.put("balance", validation.get("balance"));
        b.put("message", "指令有效，余额充足，待提交");
        return wrap(isTencent, 200, b);
    }

    // ===== 截止提交 =====

    /** 截止提交：批量落注窗口内余额充足的待提交指令。 */
    public Map<String, Object> submitPendingBets(SangongRound round, List<Long> excludeMessageIds) {
        if (round.getBetWindowOpenAt() == null || round.getBetWindowCloseMessageId() == null) {
            throw new RuntimeException("下注窗口截止点不完整");
        }
        Instant openAt = round.getBetWindowOpenAt();
        long cutoffMessageId = round.getBetWindowCloseMessageId();
        Instant closeAt = round.getBetWindowCloseAt();

        Set<Long> includedIds = new HashSet<>(messageOrder.idsAtOrBefore(round, cutoffMessageId, excludeMessageIds));
        List<SangongImMessage> pendingMessages = messages.listByRoundAndOutcomesOrdered(round.getId(),
                List.of(SangongImMessage.OUTCOME_PENDING_SUFFICIENT)).stream()
            .filter(m -> includedIds.contains(m.getId())).toList();

        List<Map<String, Object>> placed = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        for (SangongImMessage message : pendingMessages) {
            ResolvedBet resolved = resolveBetForRound(round, message.getText());
            if (resolved == null) {
                messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_BET_FAILED, "INVALID_FORMAT", null);
                failed.add(Map.of("messageId", message.getId(), "reason", "INVALID_FORMAT"));
                continue;
            }
            SangongUser user = users.findOrCreateByImUserId(message.getImUserId(), message.getNickname());
            String displayName = users.resolveNickname(user, message.getNickname());
            try {
                Map<String, Object> result = bets.placeMultiBet(user, resolved.doors(), resolved.amount(),
                    false, message.getId(), false, resolved.allIdle(), resolved.allIdleKeyword());
                long betId = ((Number) result.get("betId")).longValue();
                messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_BET_PLACED, null, betId);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("messageId", message.getId());
                item.put("betId", betId);
                item.put("betIds", result.get("betIds"));
                item.put("imUserId", user.getImUserId());
                item.put("doors", resolved.doors());
                item.put("amount", resolved.amount());
                item.put("totalAmount", result.get("totalAmount"));
                placed.add(item);
            } catch (InsufficientBalanceException e) {
                messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_BET_FAILED,
                    "INSUFFICIENT_BALANCE_AT_SUBMIT", null);
                im.notifyInsufficientBalance(user, displayName, resolved.doors().get(0),
                    resolved.amount() * resolved.doors().size(), e.getBalance());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("messageId", message.getId());
                item.put("reason", "INSUFFICIENT_BALANCE_AT_SUBMIT");
                item.put("balance", e.getBalance());
                failed.add(item);
            } catch (RuntimeException e) {
                messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_BET_FAILED, "BET_REJECTED", null);
                im.notifyBetRejected(user, displayName, e.getMessage());
                failed.add(Map.of("messageId", message.getId(), "reason", String.valueOf(e.getMessage())));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("roundId", round.getId());
        summary.put("periodNo", round.getPeriodNo());
        summary.put("windowOpenAt", TimeFmt.iso(openAt));
        summary.put("windowCloseAt", TimeFmt.iso(closeAt));
        summary.put("windowOpenMsgTime", openAt.getEpochSecond());
        summary.put("windowCloseMsgTime", closeAt != null ? closeAt.getEpochSecond() : null);
        summary.put("cutoffMessageId", cutoffMessageId);
        summary.put("excludeMessageIds", excludeMessageIds);
        summary.put("placedCount", placed.size());
        summary.put("failedCount", failed.size());
        summary.put("placed", placed);
        summary.put("failed", failed);

        SangongRound fresh = rounds.findById(round.getId());
        im.notifyBettingSummary(fresh != null ? fresh : round, round.getPeriodNo(),
            bets.getRoundBetReport(fresh != null ? fresh : round));
        realtime.touch();
        return summary;
    }

    /** 预览截止前统计：已落注 + 窗口内 pending_sufficient（不落注、不扣款）。 */
    public Map<String, Object> buildBetPreviewReport(SangongRound round, long cutoffMessageId,
                                                     List<Long> excludeMessageIds) {
        Map<String, Object> report = bets.getRoundBetReport(round);
        return mergePendingIntoReport(round, cutoffMessageId, excludeMessageIds, report, false);
    }

    /** 预览重新截止：按新截止点汇总已落注 + 窗口内待提交指令。 */
    public Map<String, Object> buildBetPreviewReportForRecutoff(SangongRound round, long cutoffMessageId,
                                                                List<Long> excludeMessageIds) {
        if (round.getBetWindowOpenAt() == null) {
            throw new RuntimeException("下注窗口未开启");
        }
        Map<String, Object> report = getRoundBetReportForCutoff(round, cutoffMessageId, excludeMessageIds);
        return mergePendingIntoReport(round, cutoffMessageId, excludeMessageIds, report, true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergePendingIntoReport(SangongRound round, long cutoffMessageId,
                                                       List<Long> excludeMessageIds,
                                                       Map<String, Object> report, boolean recutoff) {
        int doorCount = report.get("doorCount") instanceof Number n ? n.intValue() : settings.getDoorCount();
        Map<Integer, Long> doorTotals = new LinkedHashMap<>((Map<Integer, Long>) report.getOrDefault("doorTotals", Map.of()));
        long betCount = report.get("betCount") instanceof Number n ? n.longValue() : 0;

        Map<String, Map<String, Object>> usersByImId = new LinkedHashMap<>();
        for (Object o : (List<Object>) report.getOrDefault("users", List.of())) {
            Map<String, Object> userRow = (Map<String, Object>) o;
            String imUserId = String.valueOf(userRow.getOrDefault("imUserId", "")).trim();
            if (imUserId.isEmpty()) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("imUserId", imUserId);
            entry.put("nickname", String.valueOf(userRow.getOrDefault("nickname", "")));
            entry.put("doors", new LinkedHashMap<>((Map<Integer, Long>) userRow.getOrDefault("doors", Map.of())));
            entry.put("total", userRow.get("total") instanceof Number n ? n.longValue() : 0L);
            usersByImId.put(imUserId, entry);
        }

        Set<Long> includedIds = new HashSet<>(messageOrder.idsAtOrBefore(round, cutoffMessageId, excludeMessageIds));
        List<SangongImMessage> pendingMessages = new ArrayList<>(messages.listByRoundAndOutcomesOrdered(
                round.getId(), List.of(SangongImMessage.OUTCOME_PENDING_SUFFICIENT)).stream()
            .filter(m -> includedIds.contains(m.getId())).toList());

        if (recutoff) {
            List<SangongImMessage> ignored = queryIgnoredBetMessagesAtOrBeforeCutoff(round, cutoffMessageId);
            pendingMessages.addAll(ignored);
            pendingMessages.sort(messageOrder::compare);
        }

        Map<String, Long> simulatedPendingByUser = new HashMap<>();
        int pendingMessageCount = 0;
        for (SangongImMessage message : pendingMessages) {
            ResolvedBet resolved = resolveBetForRound(round, message.getText());
            if (resolved == null || resolved.doors().isEmpty() || resolved.amount() <= 0) {
                continue;
            }
            String imUserId = message.getImUserId();
            if (!SangongImMessage.OUTCOME_PENDING_SUFFICIENT.equals(message.getOutcome())
                && !wouldRevalidateAsPendingSufficient(round, message, simulatedPendingByUser)) {
                continue;
            }
            long total = resolved.amount() * resolved.doors().size();
            simulatedPendingByUser.merge(imUserId, total, Long::sum);
            SangongUser user = users.findOrCreateByImUserId(imUserId, message.getNickname());
            String nickname = users.resolveNickname(user, message.getNickname());

            Map<String, Object> entry = usersByImId.computeIfAbsent(imUserId, id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("imUserId", id);
                m.put("nickname", nickname);
                m.put("doors", new LinkedHashMap<Integer, Long>());
                m.put("total", 0L);
                return m;
            });
            Map<Integer, Long> entryDoors = (Map<Integer, Long>) entry.get("doors");
            for (int door : resolved.doors()) {
                entryDoors.merge(door, resolved.amount(), Long::sum);
                doorTotals.merge(door, resolved.amount(), Long::sum);
                betCount++;
            }
            entry.put("total", ((Number) entry.get("total")).longValue() + total);
            pendingMessageCount++;
        }

        long grandTotal = 0;
        for (int door = 1; door <= doorCount; door++) {
            grandTotal += doorTotals.getOrDefault(door, 0L);
        }

        report.put("doorTotals", doorTotals);
        report.put("betCount", betCount);
        report.put("grandTotal", grandTotal);
        report.put("pendingMessageCount", pendingMessageCount);
        report.put("users", new ArrayList<>(usersByImId.values()));
        List<Map<String, Object>> entries = buildOrderedPreviewEntries(round, cutoffMessageId, excludeMessageIds, recutoff);
        report.put("entries", entries);
        report.put("entryCount", entries.size());
        return report;
    }

    /** 按截止消息汇总已落注（不含截止之后 IM 下注；后台直下注始终计入）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getRoundBetReportForCutoff(SangongRound round, long cutoffMessageId,
                                                           List<Long> excludeMessageIds) {
        int doorCount = settings.getDoorCount();
        Set<Long> includedIds = new HashSet<>(messageOrder.idsAtOrBefore(round, cutoffMessageId, excludeMessageIds));

        String bankerNickname = null;
        String bankerImUserId = null;
        if (round.getBankerUserId() != null) {
            SangongUser banker = users.findById(round.getBankerUserId());
            if (banker != null) {
                bankerNickname = users.resolveNickname(banker);
                bankerImUserId = banker.getImUserId();
            }
        }

        Map<Integer, Long> doorTotals = new LinkedHashMap<>();
        for (int door = 1; door <= doorCount; door++) {
            doorTotals.put(door, 0L);
        }
        Map<Long, Map<String, Object>> userRows = new LinkedHashMap<>();
        long betCount = 0;
        for (var bet : betRepo.listByRound(round.getId())) {
            if (bet.getImMessageId() != null && !includedIds.contains(bet.getImMessageId())) {
                continue;
            }
            betCount++;
            SangongUser user = users.findById(bet.getUserId());
            Map<String, Object> u = userRows.computeIfAbsent(bet.getUserId(), id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("imUserId", user != null ? user.getImUserId() : "");
                m.put("nickname", user != null ? user.getNickname() : "");
                m.put("doors", new LinkedHashMap<Integer, Long>());
                m.put("total", 0L);
                return m;
            });
            Map<Integer, Long> doorsMap = (Map<Integer, Long>) u.get("doors");
            doorsMap.merge(bet.getDoor(), bet.getAmount(), Long::sum);
            u.put("total", ((Number) u.get("total")).longValue() + bet.getAmount());
            doorTotals.merge(bet.getDoor(), bet.getAmount(), Long::sum);
        }

        long grandTotal = 0;
        for (int door = 1; door <= doorCount; door++) {
            grandTotal += doorTotals.getOrDefault(door, 0L);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bankerDoor", round.getBankerDoor());
        out.put("bankerNickname", bankerNickname);
        out.put("bankerImUserId", bankerImUserId);
        out.put("doorCount", doorCount);
        out.put("doorTotals", doorTotals);
        out.put("grandTotal", grandTotal);
        out.put("betCount", betCount);
        out.put("users", new ArrayList<>(userRows.values()));
        return out;
    }

    public int countPendingAfterCutoff(SangongRound round, long cutoffMessageId) {
        Set<Long> includedIds = new HashSet<>(messageOrder.idsAtOrBefore(round, cutoffMessageId, List.of()));
        return (int) messages.listByRoundAndOutcomesOrdered(round.getId(), PENDING_OUTCOMES).stream()
            .filter(m -> !includedIds.contains(m.getId())).count();
    }

    /** 截止消息之后到达的待提交指令作废（窗口已关）。 */
    public void discardPendingAfterCutoff(SangongRound round, long cutoffMessageId) {
        Set<Long> includedIds = new HashSet<>(messageOrder.idsAtOrBefore(round, cutoffMessageId, List.of()));
        List<Long> ids = messages.listByRoundAndOutcomesOrdered(round.getId(), PENDING_OUTCOMES).stream()
            .filter(m -> !includedIds.contains(m.getId())).map(SangongImMessage::getId).toList();
        messages.markOutcomeForIds(ids, SangongImMessage.OUTCOME_IGNORED, "after_cutoff");
        realtime.touch();
    }

    /** 重新截止：撤销新截止点之外的已落注，并恢复窗口内被忽略的下注指令。 */
    public Map<String, Object> prepareBetWindowRecutoff(SangongRound round, long cutoffMessageId) {
        if (round.getBetWindowOpenAt() == null) {
            throw new RuntimeException("下注窗口未开启");
        }
        List<Map<String, Object>> cancelled = cancelPlacedImBetsAfterCutoff(round, cutoffMessageId);
        int requeued = 0;
        for (SangongImMessage message : queryIgnoredBetMessagesAtOrBeforeCutoff(round, cutoffMessageId)) {
            if (revalidatePendingBetMessage(round, message)) {
                requeued++;
            }
        }
        if (requeued > 0) {
            realtime.touch();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelled", cancelled);
        out.put("requeued", requeued);
        return out;
    }

    /** 手动排除：撤销指定 IM 消息对应已落注，并将消息标为 ignored。 */
    public List<Map<String, Object>> cancelPlacedBetsForMessageIds(SangongRound round, List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        Set<Long> idSet = new HashSet<>(messageIds);
        List<Map<String, Object>> cancelled = new ArrayList<>();
        for (SangongImMessage message : messages.listByRoundAndOutcomesOrdered(round.getId(),
                List.of(SangongImMessage.OUTCOME_BET_PLACED))) {
            if (!idSet.contains(message.getId())) continue;
            Map<String, Object> result = bets.cancelBetsByImMessageId(message.getId());
            if (!Boolean.TRUE.equals(result.get("cancelled"))) continue;
            messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_IGNORED, "excluded_manual", null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", message.getId());
            item.put("imUserId", message.getImUserId());
            item.put("doors", result.getOrDefault("doors", List.of()));
            item.put("totalAmount", result.getOrDefault("totalAmount", 0));
            cancelled.add(item);
        }
        if (!cancelled.isEmpty()) {
            realtime.touch();
        }
        return cancelled;
    }

    public List<Map<String, Object>> markExcludedPendingMessages(SangongRound round, List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        Set<Long> idSet = new HashSet<>(messageIds);
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (SangongImMessage message : messages.listByRoundAndOutcomesOrdered(round.getId(), PENDING_OUTCOMES)) {
            if (!idSet.contains(message.getId())) continue;
            messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_IGNORED, "excluded_manual", null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", message.getId());
            item.put("imUserId", message.getImUserId());
            item.put("text", message.getText());
            excluded.add(item);
        }
        if (!excluded.isEmpty()) {
            realtime.touch();
        }
        return excluded;
    }

    public Map<String, Object> applyManualExclusions(SangongRound round, List<Long> messageIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelled", cancelPlacedBetsForMessageIds(round, messageIds));
        out.put("excluded", markExcludedPendingMessages(round, messageIds));
        return out;
    }

    /** 清空本局所有未落注的预录入指令（结算后或重新开窗前调用）。 */
    public int clearRoundPendingEntries(SangongRound round, String detail) {
        int count = messages.markAllPendingIgnored(round.getId(), detail);
        if (count > 0) {
            realtime.touch();
        }
        return count;
    }

    // ===== 撤回回调 =====

    public CallbackResult handleRecall(Map<String, Object> payload, boolean isTencent) {
        String groupId = String.valueOf(payload.getOrDefault("GroupId", payload.getOrDefault("groupId", "")));
        List<Long> seqList = parseRecallSeqList(payload);
        if (groupId.isEmpty() || "null".equals(groupId) || seqList.isEmpty()) {
            return wrap(isTencent, 200, body("ok", true, "ignored", true, "reason", "invalid_recall_payload"));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (long msgSeq : seqList) {
            results.add(recallOne(groupId, msgSeq));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ok", true);
        b.put("groupId", groupId);
        b.put("recalled", results);
        return wrap(isTencent, 200, b);
    }

    private Map<String, Object> recallOne(String groupId, long msgSeq) {
        SangongImMessage message = messages.findByGroupAndSeq(groupId, msgSeq).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (message == null) {
            out.put("msgSeq", msgSeq);
            out.put("deleted", false);
            out.put("reason", "message_not_found");
            return out;
        }
        boolean betCancelled = false;
        String betCancelReason = null;
        if (message.getBetId() != null || SangongImMessage.OUTCOME_BET_PLACED.equals(message.getOutcome())) {
            try {
                Map<String, Object> cancel = bets.cancelBetsByImMessageId(message.getId());
                if (!Boolean.TRUE.equals(cancel.get("cancelled")) && message.getBetId() != null) {
                    cancel = bets.cancelBetByRecall(message.getBetId());
                }
                betCancelled = Boolean.TRUE.equals(cancel.get("cancelled"));
                betCancelReason = cancel.get("reason") != null ? String.valueOf(cancel.get("reason")) : null;
                if (betCancelled) {
                    SangongUser user = users.findById(((Number) cancel.get("userId")).longValue());
                    if (user != null) {
                        String displayName = users.resolveNickname(user);
                        long totalAmount = cancel.get("totalAmount") instanceof Number n ? n.longValue()
                            : (cancel.get("amount") instanceof Number a ? a.longValue() : 0);
                        int door = cancel.get("door") instanceof Number n ? n.intValue() : 0;
                        long balance = cancel.get("balance") instanceof Number n ? n.longValue() : 0;
                        im.notifyBetRecalled(user, displayName, door, totalAmount, balance);
                    }
                }
            } catch (RuntimeException e) {
                betCancelReason = e.getMessage();
            }
        } else if (PENDING_OUTCOMES.contains(message.getOutcome())) {
            betCancelReason = "pending_removed";
        }
        long messageId = message.getId();
        Long betId = message.getBetId();
        messages.deleteById(messageId);
        realtime.touch();

        out.put("msgSeq", msgSeq);
        out.put("deleted", true);
        out.put("messageId", messageId);
        out.put("betId", betId);
        out.put("betCancelled", betCancelled);
        out.put("betCancelReason", betCancelReason);
        return out;
    }

    // ===== 窗口内待提交统计 =====

    public Map<String, Object> getPendingDoorStats(SangongRound round) {
        int doorCount = settings.getDoorCount();
        Map<Integer, Long> doorTotals = new LinkedHashMap<>();
        for (int door = 1; door <= doorCount; door++) {
            doorTotals.put(door, 0L);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (SangongRound.SETTLED.equals(round.getStatus()) || !round.isBetWindowOpen()) {
            out.put("open", false);
            out.put("messageCount", 0);
            out.put("doorTotals", doorTotals);
            out.put("grandTotal", 0L);
            return out;
        }
        int messageCount = 0;
        for (SangongImMessage message : messages.listByRoundAndOutcomesOrdered(round.getId(),
                List.of(SangongImMessage.OUTCOME_PENDING_SUFFICIENT))) {
            ResolvedBet resolved = resolveBetForRound(round, message.getText());
            if (resolved == null) continue;
            messageCount++;
            for (int door : resolved.doors()) {
                doorTotals.merge(door, resolved.amount(), Long::sum);
            }
        }
        out.put("open", true);
        out.put("messageCount", messageCount);
        out.put("doorTotals", doorTotals);
        out.put("grandTotal", doorTotals.values().stream().mapToLong(Long::longValue).sum());
        return out;
    }

    // ===== 内部 =====

    private record ParsedSend(String imUserId, String groupId, String text, String nickname,
                              Long msgSeq, String msgId, Long msgTime, String command) {}

    private record ResolvedBet(List<Integer> doors, long amount, boolean allIdle, String allIdleKeyword) {}

    private List<Map<String, Object>> buildOrderedPreviewEntries(SangongRound round, long cutoffMessageId,
                                                                 List<Long> excludeMessageIds,
                                                                 boolean includePlaced) {
        List<Long> includedIds = messageOrder.idsAtOrBefore(round, cutoffMessageId, excludeMessageIds);
        List<Map<String, Object>> entries = new ArrayList<>();
        int index = 0;
        Map<String, Long> simulatedPendingByUser = new HashMap<>();

        if (!includedIds.isEmpty()) {
            Set<Long> idSet = new LinkedHashSet<>(includedIds);
            List<SangongImMessage> ordered = messages.listByRoundOrdered(round.getId()).stream()
                .filter(m -> idSet.contains(m.getId())).toList();
            for (SangongImMessage message : ordered) {
                Map<String, Object> entry = buildPreviewEntryFromMessage(round, message, includePlaced, simulatedPendingByUser);
                if (entry == null) continue;
                index++;
                entry.put("index", index);
                entries.add(entry);
            }
        }
        return appendAdminBetEntries(round, entries, index);
    }

    private Map<String, Object> buildPreviewEntryFromMessage(SangongRound round, SangongImMessage message,
                                                             boolean includePlaced,
                                                             Map<String, Long> simulatedPendingByUser) {
        ResolvedBet resolved = resolveBetForRound(round, message.getText());
        if (resolved == null || resolved.doors().isEmpty() || resolved.amount() <= 0) {
            return null;
        }
        String status;
        String outcome = message.getOutcome() == null ? "" : message.getOutcome();
        if (SangongImMessage.OUTCOME_BET_PLACED.equals(outcome)) {
            if (!includePlaced) return null;
            status = "placed";
        } else if (SangongImMessage.OUTCOME_PENDING_SUFFICIENT.equals(outcome)) {
            status = "pending";
        } else if (SangongImMessage.OUTCOME_IGNORED.equals(outcome)
            && List.of("after_cutoff", "bet_window_closed").contains(String.valueOf(message.getOutcomeDetail()))) {
            if (!includePlaced) return null;
            if (!wouldRevalidateAsPendingSufficient(round, message, simulatedPendingByUser)) return null;
            status = "pending";
        } else {
            return null;
        }
        if ("pending".equals(status)) {
            simulatedPendingByUser.merge(message.getImUserId(),
                resolved.amount() * resolved.doors().size(), Long::sum);
        }
        SangongUser user = users.findOrCreateByImUserId(message.getImUserId(), message.getNickname());
        String nickname = users.resolveNickname(user, message.getNickname());

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("messageId", message.getId());
        entry.put("msgSeq", message.getMsgSeq());
        entry.put("msgTime", message.getMsgTime());
        entry.put("imUserId", message.getImUserId());
        entry.put("nickname", nickname);
        entry.put("text", message.getText());
        entry.put("doors", resolved.doors());
        entry.put("amount", resolved.amount());
        entry.put("totalAmount", resolved.amount() * resolved.doors().size());
        entry.put("doorCount", resolved.doors().size());
        entry.put("status", status);
        entry.put("outcome", outcome);
        entry.put("source", "im");
        return entry;
    }

    private List<Map<String, Object>> appendAdminBetEntries(SangongRound round,
                                                            List<Map<String, Object>> entries, int index) {
        List<Map<String, Object>> rows = betRepo.adminBetRows(round.getId());
        if (rows.isEmpty()) {
            return entries;
        }
        Map<Long, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long userId = ((Number) row.get("user_id")).longValue();
            Map<String, Object> g = grouped.computeIfAbsent(userId, id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("imUserId", String.valueOf(row.get("im_user_id")));
                m.put("nickname", String.valueOf(row.get("nickname")));
                m.put("doors", new LinkedHashMap<Integer, Long>());
                m.put("total", 0L);
                return m;
            });
            int door = ((Number) row.get("door")).intValue();
            long amount = ((Number) row.get("amount")).longValue();
            @SuppressWarnings("unchecked")
            Map<Integer, Long> doorsMap = (Map<Integer, Long>) g.get("doors");
            doorsMap.merge(door, amount, Long::sum);
            g.put("total", ((Number) g.get("total")).longValue() + amount);
        }
        for (Map<String, Object> userRow : grouped.values()) {
            @SuppressWarnings("unchecked")
            Map<Integer, Long> doorAmounts = (Map<Integer, Long>) userRow.get("doors");
            List<Integer> doors = new ArrayList<>(doorAmounts.keySet());
            doors.sort(Integer::compareTo);
            index++;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", index);
            entry.put("messageId", null);
            entry.put("msgSeq", null);
            entry.put("msgTime", null);
            entry.put("imUserId", userRow.get("imUserId"));
            entry.put("nickname", userRow.get("nickname"));
            entry.put("text", null);
            entry.put("doors", doors);
            entry.put("amount", null);
            entry.put("totalAmount", userRow.get("total"));
            entry.put("doorCount", doors.size());
            entry.put("doorAmounts", doorAmounts);
            entry.put("status", "placed");
            entry.put("outcome", "bet_placed");
            entry.put("source", "admin");
            entries.add(entry);
        }
        return entries;
    }

    private List<Map<String, Object>> cancelPlacedImBetsAfterCutoff(SangongRound round, long cutoffMessageId) {
        Set<Long> includedIds = new HashSet<>(messageOrder.idsAtOrBefore(round, cutoffMessageId, List.of()));
        List<Map<String, Object>> cancelled = new ArrayList<>();
        for (SangongImMessage message : messages.listByRoundAndOutcomesOrdered(round.getId(),
                List.of(SangongImMessage.OUTCOME_BET_PLACED))) {
            if (includedIds.contains(message.getId())) continue;
            Map<String, Object> result = bets.cancelBetsByImMessageId(message.getId());
            if (!Boolean.TRUE.equals(result.get("cancelled"))) continue;
            messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_IGNORED, "after_cutoff", null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", message.getId());
            item.put("imUserId", message.getImUserId());
            item.put("doors", result.getOrDefault("doors", List.of()));
            item.put("totalAmount", result.getOrDefault("totalAmount", 0));
            cancelled.add(item);
        }
        if (!cancelled.isEmpty()) {
            realtime.touch();
        }
        return cancelled;
    }

    private List<SangongImMessage> queryIgnoredBetMessagesAtOrBeforeCutoff(SangongRound round, long cutoffMessageId) {
        Set<Long> includedIds = new HashSet<>(messageOrder.idsAtOrBefore(round, cutoffMessageId, List.of()));
        return messages.listByRoundAndOutcomesOrdered(round.getId(), List.of(SangongImMessage.OUTCOME_IGNORED)).stream()
            .filter(m -> List.of("after_cutoff", "bet_window_closed").contains(String.valueOf(m.getOutcomeDetail())))
            .filter(m -> includedIds.contains(m.getId()))
            .toList();
    }

    private boolean wouldRevalidateAsPendingSufficient(SangongRound round, SangongImMessage message,
                                                       Map<String, Long> simulatedPendingByUser) {
        BetTextParser.Parsed parsed = betTextParser.parse(message.getText());
        if (parsed == null) return false;
        ResolvedBet resolved = resolveBetForRound(round, parsed);
        if (resolved == null) return false;
        String imUserId = message.getImUserId();
        SangongUser user = users.findOrCreateByImUserId(imUserId, message.getNickname());
        long pendingReserved = sumUserPendingSufficientAmount(round, imUserId)
            + simulatedPendingByUser.getOrDefault(imUserId, 0L);
        Map<String, Object> validation = bets.validateMultiBetCommand(user, resolved.doors(), resolved.amount(),
            true, pendingReserved);
        return Boolean.TRUE.equals(validation.get("ok"));
    }

    private boolean revalidatePendingBetMessage(SangongRound round, SangongImMessage message) {
        BetTextParser.Parsed parsed = betTextParser.parse(message.getText());
        if (parsed == null) return false;
        ResolvedBet resolved = resolveBetForRound(round, parsed);
        String imUserId = message.getImUserId();
        if (resolved == null) {
            String reason = betTextParser.resolveRejectReason(parsed, settings.getDoorCount(), round.getBankerDoor());
            messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_PENDING_REJECTED,
                outcomeDetailForRejectReason(reason), null);
            return true;
        }
        SangongUser user = users.findOrCreateByImUserId(imUserId, message.getNickname());
        long pendingReserved = sumUserPendingSufficientAmount(round, imUserId);
        Map<String, Object> validation = bets.validateMultiBetCommand(user, resolved.doors(), resolved.amount(),
            true, pendingReserved);
        if (!Boolean.TRUE.equals(validation.get("ok"))) {
            String code = String.valueOf(validation.getOrDefault("code", "BET_REJECTED"));
            if ("INSUFFICIENT_BALANCE".equals(code)) {
                messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_PENDING_INSUFFICIENT,
                    "INSUFFICIENT_BALANCE", null);
            } else {
                messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_PENDING_REJECTED, code, null);
            }
            return true;
        }
        messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_PENDING_SUFFICIENT, null, null);
        return true;
    }

    private long sumUserPendingSufficientAmount(SangongRound round, String imUserId) {
        long total = 0;
        for (SangongImMessage message : messages.listByRoundAndOutcomesOrdered(round.getId(),
                List.of(SangongImMessage.OUTCOME_PENDING_SUFFICIENT))) {
            if (!imUserId.equals(message.getImUserId())) continue;
            ResolvedBet resolved = resolveBetForRound(round, message.getText());
            if (resolved != null) {
                total += resolved.amount() * resolved.doors().size();
            }
        }
        return total;
    }

    private ResolvedBet resolveBetForRound(SangongRound round, String text) {
        return resolveBetForRound(round, betTextParser.parse(text));
    }

    private ResolvedBet resolveBetForRound(SangongRound round, BetTextParser.Parsed parsed) {
        if (parsed == null) return null;
        BetTextParser.Resolved resolved = betTextParser.resolve(parsed, settings.getDoorCount(), round.getBankerDoor());
        if (resolved == null) return null;
        return new ResolvedBet(resolved.doors(), resolved.amount(), parsed.isAllIdle(),
            betTextParser.allIdleKeyword(parsed));
    }

    private String outcomeDetailForRejectReason(String reason) {
        if ("尚未定庄门，无法使用全门下注".equals(reason)) return "NO_BANKER_DOOR";
        if ("庄门不可下注".equals(reason)) return "BANKER_DOOR";
        return "INVALID_BET";
    }

    private boolean isGameGroup(String groupId) {
        String gameGroupId = settings.getImGroupGameId();
        return gameGroupId.isEmpty() || groupId.equals(gameGroupId);
    }

    private CallbackResult finalizeIgnored(SangongImMessage message, String reason, boolean isTencent) {
        messages.updateOutcome(message.getId(), SangongImMessage.OUTCOME_IGNORED, reason, null);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ok", true);
        b.put("ignored", true);
        b.put("reason", reason);
        b.put("messageId", message.getId());
        return wrap(isTencent, 200, b);
    }

    private CallbackResult buildSendResultFromMessage(SangongImMessage message, boolean isTencent, boolean duplicate) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ok", true);
        b.put("duplicate", duplicate);
        b.put("messageId", message.getId());
        b.put("outcome", message.getOutcome());
        if (SangongImMessage.OUTCOME_IGNORED.equals(message.getOutcome())) {
            b.put("ignored", true);
            b.put("reason", message.getOutcomeDetail());
        }
        if (message.getBetId() != null) {
            b.put("betId", message.getBetId());
        }
        return wrap(isTencent, 200, b);
    }

    private ParsedSend parseSendPayload(Map<String, Object> payload) {
        String command = str(payload.get("CallbackCommand"));
        if ("Group.CallbackAfterSendMsg".equals(command)) {
            return parseTencentSend(payload);
        }
        if (!command.isEmpty() && command.startsWith("Group.Callback")) {
            return null;
        }
        String action = str(payload.getOrDefault("action", "send")).toLowerCase();
        if ("recall".equals(action)) {
            return null;
        }
        return parseSimpleSend(payload);
    }

    private ParsedSend parseTencentSend(Map<String, Object> payload) {
        String groupId = str(payload.get("GroupId"));
        String imUserId = str(payload.get("From_Account"));
        if (groupId.isEmpty() || imUserId.isEmpty()) {
            return null;
        }
        String text = extractTextFromMsgBody(payload.get("MsgBody"));
        Long msgSeq = num(payload.get("MsgSeq"));
        String msgId = payload.containsKey("MsgId") ? str(payload.get("MsgId")) : null;
        Long msgTime = num(payload.get("MsgTime"));
        return new ParsedSend(imUserId, groupId, text == null ? "" : text, null,
            msgSeq, msgId, msgTime, "Group.CallbackAfterSendMsg");
    }

    private ParsedSend parseSimpleSend(Map<String, Object> payload) {
        String imUserId = str(payload.getOrDefault("imUserId", payload.getOrDefault("fromAccount", ""))).trim();
        String groupId = str(payload.getOrDefault("groupId", payload.getOrDefault("GroupId", ""))).trim();
        String text = str(payload.getOrDefault("text", payload.getOrDefault("message", ""))).trim();
        if (imUserId.isEmpty() || groupId.isEmpty()) {
            return null;
        }
        String nickname = null;
        Object nick = payload.getOrDefault("nickname", payload.get("nick"));
        if (nick != null) {
            nickname = str(nick).trim();
            if (nickname.isEmpty()) nickname = null;
        }
        Long msgSeq = num(payload.getOrDefault("msgSeq", payload.get("MsgSeq")));
        Object msgIdObj = payload.getOrDefault("msgId", payload.get("MsgId"));
        String msgId = msgIdObj != null ? str(msgIdObj) : null;
        Long msgTime = num(payload.getOrDefault("msgTime", payload.get("MsgTime")));
        String command = payload.containsKey("CallbackCommand") ? str(payload.get("CallbackCommand")) : "simple.send";
        return new ParsedSend(imUserId, groupId, text, nickname, msgSeq, msgId, msgTime, command);
    }

    private List<Long> parseRecallSeqList(Map<String, Object> payload) {
        String command = str(payload.get("CallbackCommand"));
        if ("Group.CallbackAfterRecallMsg".equals(command)) {
            return seqList(payload.get("MsgSeqList"));
        }
        if (payload.containsKey("msgSeq")) {
            Long seq = num(payload.get("msgSeq"));
            return seq != null ? List.of(seq) : List.of();
        }
        if (payload.containsKey("msgSeqList")) {
            return seqList(payload.get("msgSeqList"));
        }
        if (payload.containsKey("MsgSeqList")) {
            return seqList(payload.get("MsgSeqList"));
        }
        return List.of();
    }

    private List<Long> seqList(Object list) {
        if (!(list instanceof List<?> items)) {
            return List.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> m) {
                Long seq = num(m.containsKey("MsgSeq") ? m.get("MsgSeq") : m.get("msgSeq"));
                if (seq != null) out.add(seq);
            } else {
                Long seq = num(item);
                if (seq != null) out.add(seq);
            }
        }
        return new ArrayList<>(out);
    }

    private String extractTextFromMsgBody(Object msgBody) {
        if (!(msgBody instanceof List<?> items)) {
            return null;
        }
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> elem)) continue;
            if (!"TIMTextElem".equals(elem.get("MsgType"))) continue;
            Object content = elem.get("MsgContent");
            if (!(content instanceof Map<?, ?> c)) continue;
            String text = str(c.get("Text")).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("raw payload serialize failed: {}", e.getMessage());
            return "{}";
        }
    }

    private static CallbackResult wrap(boolean isTencent, int status, Map<String, Object> body) {
        return new CallbackResult(isTencent, status, body);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Long num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
