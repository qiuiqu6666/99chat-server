package com.chat99.sangong.service;

import com.chat99.sangong.common.InsufficientBalanceException;
import com.chat99.sangong.common.TimeFmt;
import com.chat99.sangong.domain.SangongBet;
import com.chat99.sangong.domain.SangongImMessage;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongSession;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.BetRepository;
import com.chat99.sangong.repository.CoBankRepository;
import com.chat99.sangong.repository.DrawRepository;
import com.chat99.sangong.repository.ImMessageRepository;
import com.chat99.sangong.repository.RoundRepository;
import com.chat99.sangong.repository.SessionRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 对局流程：新局、定庄、合庄、下注窗口、作废（与 PHP RoundService 一致）。 */
@Service
public class RoundService {
    public record BeginResult(SangongRound round, boolean newRound) {}
    public record SetupResult(SangongRound round, boolean newRound) {}
    public record NotifyResult(boolean sent, boolean restarted, boolean newRound) {}

    private final ImService im;
    private final UserService users;
    private final BalanceService balance;
    private final GameSettingsService gameSettings;
    private final CoBankService coBankService;
    private final RoundRepository rounds;
    private final SessionRepository sessions;
    private final BetRepository bets;
    private final DrawRepository draws;
    private final CoBankRepository coBanks;
    private final ImMessageRepository messages;
    private final ImMessageService imMessages;
    private final BetWindowMessageOrder messageOrder;
    private final RealtimeVersionStore realtime;
    private final TurnoverService turnover;

    public RoundService(ImService im, UserService users, BalanceService balance,
                        GameSettingsService gameSettings, CoBankService coBankService,
                        RoundRepository rounds, SessionRepository sessions, BetRepository bets,
                        DrawRepository draws, CoBankRepository coBanks, ImMessageRepository messages,
                        @Lazy ImMessageService imMessages, @Lazy BetWindowMessageOrder messageOrder,
                        RealtimeVersionStore realtime, TurnoverService turnover) {
        this.im = im;
        this.users = users;
        this.balance = balance;
        this.gameSettings = gameSettings;
        this.coBankService = coBankService;
        this.rounds = rounds;
        this.sessions = sessions;
        this.bets = bets;
        this.draws = draws;
        this.coBanks = coBanks;
        this.messages = messages;
        this.imMessages = imMessages;
        this.messageOrder = messageOrder;
        this.realtime = realtime;
        this.turnover = turnover;
    }

    public SangongRound createForSession(SangongSession session, int periodNo) {
        return rounds.insert(session.getId(), periodNo, SangongRound.AWAIT_BANKER);
    }

    public SangongRound getCurrent() {
        SangongSession session = sessions.findRunning().orElse(null);
        if (session == null || session.getCurrentRoundId() == null) {
            return null;
        }
        return rounds.findById(session.getCurrentRoundId()).orElse(null);
    }

    public SangongRound findById(long id) {
        return rounds.findById(id).orElse(null);
    }

    public SangongRound startNewRound() {
        return startNewRound(false);
    }

    @Transactional
    public SangongRound startNewRound(boolean notify) {
        SangongSession session = sessions.findRunning().orElse(null);
        if (session == null) {
            throw new RuntimeException("未开机，不可开新局");
        }
        SangongRound current = session.getCurrentRoundId() != null
            ? rounds.findById(session.getCurrentRoundId()).orElse(null) : null;
        if (current != null && !SangongRound.SETTLED.equals(current.getStatus())) {
            throw new RuntimeException("当前局尚未结算，不可开新局");
        }
        int periodNo = session.getCurrentPeriodNo() + 1;
        SangongRound round = createForSession(session, periodNo);
        session.setCurrentPeriodNo(periodNo);
        session.setCurrentRoundId(round.getId());
        sessions.update(session);
        if (notify) {
            im.notifyNewRound(round.getPeriodNo());
        }
        return round;
    }

    /** 结算后开下一期；若会话已在进行中的新局则直接返回该局。 */
    public BeginResult beginNextRoundIfNeeded(SangongRound round) {
        if (round == null) {
            round = getCurrent();
        }
        if (round == null) {
            return new BeginResult(startNewRound(false), true);
        }
        if (!SangongRound.SETTLED.equals(round.getStatus())) {
            return new BeginResult(round, false);
        }
        SangongRound current = getCurrent();
        if (current != null && current.getId() != round.getId()
            && !SangongRound.SETTLED.equals(current.getStatus())) {
            return new BeginResult(current, false);
        }
        return new BeginResult(startNewRound(false), true);
    }

    /** 定庄 / 修改定庄。 */
    public SetupResult setupBanker(SangongRound round, SangongUser user, int door, long bankerLimit) {
        BeginResult begin = beginNextRoundIfNeeded(round);
        round = begin.round();
        boolean newRound = begin.newRound();

        List<String> allowed = List.of(SangongRound.AWAIT_BANKER, SangongRound.AWAIT_BANKER_DOOR,
            SangongRound.BETTING, SangongRound.CO_BANK_CLOSED);
        if (!allowed.contains(round.getStatus())) {
            throw new RuntimeException("当前阶段不可定庄");
        }
        if (door < 1 || door > gameSettings.getDoorCount()) {
            throw new RuntimeException("庄门须为 1-" + gameSettings.getDoorCount());
        }
        if (bankerLimit < 0) {
            throw new RuntimeException("限额不能为负数");
        }

        im.recallCoBankSummaryMessage(round);
        coBanks.deleteByRound(round.getId());

        round.setBankerUserId(user.getId());
        round.setBankerDoor(door);
        round.setBankerLimit(bankerLimit > 0 ? bankerLimit : null);
        round.setStatus(SangongRound.BETTING);
        if (bankerLimit > 0) {
            round.setCoBankClosedAt(null);
        }
        rounds.save(round);

        realtime.touch();
        return new SetupResult(rounds.findById(round.getId()).orElse(round), newRound);
    }

    /** 快速定庄：定庄 + 群通知 + 开启下注窗口。 */
    public Map<String, Object> quickSetupBanker(SangongRound round, SangongUser user, int door,
                                                long bankerLimit, String sourceText) {
        boolean wasAwaitBanker = SangongRound.AWAIT_BANKER.equals(round.getStatus());
        SetupResult setup = setupBanker(round, user, door, bankerLimit);
        round = setup.round();
        boolean newRound = setup.newRound();

        NotifyResult notify = sendBankerNotify(round);
        SangongRound current = getCurrent();
        round = current != null ? current : round;

        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("door", door);
        parsed.put("limit", bankerLimit);
        parsed.put("limited", bankerLimit > 0);
        parsed.put("text", sourceText);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("round", round);
        out.put("newRound", newRound);
        out.put("replaced", !wasAwaitBanker && !newRound);
        out.put("sent", notify.sent());
        out.put("restarted", notify.restarted());
        out.put("parsed", parsed);
        return out;
    }

    public SangongRound setBanker(SangongRound round, long userId) {
        if (!SangongRound.AWAIT_BANKER.equals(round.getStatus())) {
            throw new RuntimeException("当前阶段不可定庄");
        }
        SangongUser user = users.findById(userId);
        if (user == null) {
            throw new RuntimeException("庄主用户不存在");
        }
        round.setBankerUserId(user.getId());
        round.setStatus(SangongRound.AWAIT_BANKER_DOOR);
        rounds.save(round);
        realtime.touch();
        return rounds.findById(round.getId()).orElse(round);
    }

    public SangongRound setBankerDoor(SangongRound round, int door) {
        if (!SangongRound.AWAIT_BANKER_DOOR.equals(round.getStatus())) {
            throw new RuntimeException("当前阶段不可选庄门");
        }
        if (door < 1 || door > gameSettings.getDoorCount()) {
            throw new RuntimeException("庄门须为 1-" + gameSettings.getDoorCount());
        }
        round.setBankerDoor(door);
        round.setStatus(SangongRound.BETTING);
        rounds.save(round);
        realtime.touch();
        return rounds.findById(round.getId()).orElse(round);
    }

    public boolean isCoBankEnabled(SangongRound round) {
        return round.getBankerLimit() != null && round.getBankerLimit() > 0;
    }

    /** 录入合庄占股：仅记录出资金额，不扣款；同一用户以最新金额为准。 */
    public SangongRound addCoBank(SangongRound round, long userId, long amount) {
        if (!isCoBankEnabled(round)) {
            throw new RuntimeException("本局不限额，不可合庄");
        }
        if (!List.of(SangongRound.BETTING, SangongRound.CO_BANK_CLOSED).contains(round.getStatus())) {
            throw new RuntimeException("当前阶段不可录入合庄");
        }
        if (round.getCoBankClosedAt() != null) {
            throw new RuntimeException("合庄已截止，不可追加出资");
        }
        if (amount <= 0) {
            throw new RuntimeException("出资金额须为正整数");
        }
        SangongUser user = users.findById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (bets.existsByRoundAndUser(round.getId(), userId)) {
            throw new RuntimeException("已下注用户不可合庄");
        }
        if (round.getBankerUserId() != null && round.getBankerUserId() == userId) {
            throw new RuntimeException("庄主不可合庄");
        }
        if (!balance.hasEnough(user, amount)) {
            throw new InsufficientBalanceException(balance.getBalance(user));
        }
        coBanks.upsert(round.getId(), userId, amount);
        return rounds.findById(round.getId()).orElse(round);
    }

    /** 取消某人合庄。 */
    public Map<String, Object> removeCoBank(SangongRound round, long userId) {
        if (!isCoBankEnabled(round)) {
            throw new RuntimeException("本局不限额，不可合庄");
        }
        if (!List.of(SangongRound.BETTING, SangongRound.CO_BANK_CLOSED).contains(round.getStatus())) {
            throw new RuntimeException("当前阶段不可取消合庄");
        }
        if (round.getDrawLockedAt() != null) {
            throw new RuntimeException("已录入开彩，不可取消合庄");
        }
        if (userId <= 0) {
            throw new RuntimeException("用户无效");
        }
        if (round.getBankerUserId() != null && round.getBankerUserId() == userId) {
            throw new RuntimeException("原庄家出资不可取消，请重新定庄");
        }
        var existing = coBanks.findByRoundAndUser(round.getId(), userId)
            .orElseThrow(() -> new RuntimeException("该用户未录入合庄"));
        long removedAmount = existing.getAmount();
        coBanks.deleteByRoundAndUser(round.getId(), userId);
        realtime.touch();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("removedAmount", removedAmount);
        return out;
    }

    public Map<String, Object> getCoBankSummary(long roundId) {
        return coBankService.getCoBankSummary(roundId);
    }

    public SangongRound closeCoBank(SangongRound round) {
        if (!List.of(SangongRound.BETTING, SangongRound.CO_BANK_CLOSED).contains(round.getStatus())) {
            throw new RuntimeException("当前阶段不可截止合庄");
        }
        if (SangongRound.CO_BANK_CLOSED.equals(round.getStatus())) {
            return round;
        }
        round.setStatus(SangongRound.CO_BANK_CLOSED);
        round.setCoBankClosedAt(Instant.now());
        rounds.save(round);
        im.notifyCoBankClosed(round.getPeriodNo());
        return rounds.findById(round.getId()).orElse(round);
    }

    /** 关机时作废未结算局：退还已落注、清空开彩，标记 voided。 */
    @Transactional
    public Map<String, Object> voidUnsettledRound(SangongRound roundRef) {
        if (SangongRound.SETTLED.equals(roundRef.getStatus())) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("roundId", roundRef.getId());
        summary.put("periodNo", roundRef.getPeriodNo());
        summary.put("refundedBetCount", 0);
        summary.put("refundedAmount", 0L);
        if (SangongRound.VOIDED.equals(roundRef.getStatus())) {
            return summary;
        }

        SangongRound round = rounds.lockById(roundRef.getId());
        if (round == null) {
            throw new RuntimeException("局不存在");
        }
        if (SangongRound.SETTLED.equals(round.getStatus()) || SangongRound.VOIDED.equals(round.getStatus())) {
            return summary;
        }
        long sessionId = round.getSessionId();
        int refundedBetCount = 0;
        long refundedAmount = 0;
        List<SangongBet> existingBets = bets.listByRound(round.getId());
        for (SangongBet bet : existingBets) {
            SangongUser user = users.findById(bet.getUserId());
            long amount = bet.getAmount();
            if (user != null) {
                balance.releaseHold(user, amount, "bet_void", sessionId, "关机作废本局退还下注");
            }
            refundedBetCount++;
            refundedAmount += amount;
        }
        if (refundedAmount > 0) {
            java.util.Map<Long, Long> refundedByUser = new java.util.LinkedHashMap<>();
            for (SangongBet bet : existingBets) {
                refundedByUser.merge(bet.getUserId(), bet.getAmount(), Long::sum);
            }
            refundedByUser.forEach((userId, amount) -> turnover.removePlayerTurnover(userId, amount, sessionId));
        }
        bets.deleteByRound(round.getId());
        draws.deleteByRound(round.getId());
        messages.detachRound(round.getId(), "session_stopped");
        im.recallCoBankSummaryMessage(round);
        round.setStatus(SangongRound.VOIDED);
        rounds.save(round);

        summary.put("refundedBetCount", refundedBetCount);
        summary.put("refundedAmount", refundedAmount);
        realtime.touch();
        return summary;
    }

    public Map<String, Object> formatRound(SangongRound round) {
        SangongUser banker = round.getBankerUserId() != null ? users.findById(round.getBankerUserId()) : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", round.getId());
        out.put("sessionId", round.getSessionId());
        out.put("periodNo", round.getPeriodNo());
        out.put("status", round.getStatus());
        out.put("bankerUserId", round.getBankerUserId());
        out.put("bankerNickname", banker != null ? banker.getNickname() : null);
        out.put("bankerDoor", round.getBankerDoor());
        out.put("bankerLimit", round.getBankerLimit());
        out.put("coBankClosedAt", TimeFmt.iso(round.getCoBankClosedAt()));
        out.put("betWindowOpenAt", TimeFmt.iso(round.getBetWindowOpenAt()));
        out.put("betWindowCloseAt", TimeFmt.iso(round.getBetWindowCloseAt()));
        out.put("betWindowCloseMessageId", round.getBetWindowCloseMessageId());
        out.put("drawLockedAt", TimeFmt.iso(round.getDrawLockedAt()));
        out.put("settledAt", TimeFmt.iso(round.getSettledAt()));
        out.put("coBank", getCoBankSummary(round.getId()));
        return out;
    }

    /** 发送定庄通知并开启下注窗口；若本局已有下注/开彩则推倒重开。 */
    public NotifyResult sendBankerNotify(SangongRound round) {
        BeginResult begin = beginNextRoundIfNeeded(round);
        round = begin.round();
        boolean newRound = begin.newRound();

        if (SangongRound.SETTLED.equals(round.getStatus())) {
            throw new RuntimeException("本局已结算，请先定庄再发送");
        }
        if (round.getBankerUserId() == null || round.getBankerDoor() == null) {
            if (newRound) {
                throw new RuntimeException("请先定庄，再发送开启下一局");
            }
            throw new RuntimeException("尚未定庄或庄门");
        }
        boolean restarted = false;
        if (roundHasGameplay(round)) {
            round = restartRoundInPlace(round);
            restarted = true;
        }
        SangongUser banker = users.findById(round.getBankerUserId());
        if (banker == null) {
            throw new RuntimeException("庄主不存在");
        }
        String bankerNick = users.resolveNickname(banker);
        Long limit = round.getBankerLimit() != null && round.getBankerLimit() > 0 ? round.getBankerLimit() : null;
        boolean sent = im.notifyBankerSetup(bankerNick, round.getBankerDoor(), limit);
        openBetWindow(round);
        return new NotifyResult(sent, restarted, newRound);
    }

    public boolean roundHasGameplay(SangongRound round) {
        if (round.getBetWindowOpenAt() != null) return true;
        if (round.getBetWindowCloseAt() != null) return true;
        if (round.getDrawLockedAt() != null) return true;
        if (bets.countByRound(round.getId()) > 0) return true;
        return draws.existsByRound(round.getId());
    }

    /** 推倒重开本局：退还下注、清空开彩与窗口，期号不变。 */
    @Transactional
    public SangongRound restartRoundInPlace(SangongRound roundRef) {
        if (SangongRound.SETTLED.equals(roundRef.getStatus())) {
            throw new RuntimeException("本局已结算，不可重开");
        }
        SangongRound round = rounds.lockById(roundRef.getId());
        if (round == null) {
            throw new RuntimeException("局不存在");
        }
        long sessionId = round.getSessionId();
        List<SangongBet> existingBets = bets.listByRound(round.getId());
        for (SangongBet bet : existingBets) {
            SangongUser user = users.findById(bet.getUserId());
            if (user != null) {
                balance.releaseHold(user, bet.getAmount(), "bet_restart", sessionId, "本局推倒重开退还下注");
            }
        }
        java.util.Map<Long, Long> refundedByUser = new java.util.LinkedHashMap<>();
        for (SangongBet bet : existingBets) {
            refundedByUser.merge(bet.getUserId(), bet.getAmount(), Long::sum);
        }
        refundedByUser.forEach((userId, amount) -> turnover.removePlayerTurnover(userId, amount, sessionId));
        bets.deleteByRound(round.getId());
        draws.deleteByRound(round.getId());
        messages.detachRound(round.getId(), "round_restarted");

        round.setBetWindowOpenAt(null);
        round.setBetWindowCloseAt(null);
        round.setBetWindowCloseMessageId(null);
        round.setBetSummaryTextMsgSeq(null);
        round.setBetSummaryImageMsgSeq(null);
        round.setDrawLockedAt(null);
        round.setCoBankClosedAt(null);
        round.setStatus(SangongRound.BETTING);
        rounds.save(round);
        return rounds.findById(round.getId()).orElse(round);
    }

    public SangongRound openBetWindow(SangongRound round) {
        imMessages.clearRoundPendingEntries(round, "window_reopened");
        round.setBetWindowOpenAt(Instant.now());
        round.setBetWindowCloseAt(null);
        round.setBetWindowCloseMessageId(null);
        round.setBetSummaryTextMsgSeq(null);
        round.setBetSummaryImageMsgSeq(null);
        rounds.save(round);
        realtime.touch();
        return rounds.findById(round.getId()).orElse(round);
    }

    /** 截止提交：关闭窗口并批量落注窗口内有效指令。 */
    public Map<String, Object> submitBetWindow(SangongRound round, Long untilMessageId,
                                               Long untilMsgSeq, List<Long> excludeMessageIds) {
        if (round.getBetWindowOpenAt() == null) {
            throw new RuntimeException("尚未发送定庄，下注窗口未开启");
        }
        if (SangongRound.SETTLED.equals(round.getStatus())) {
            throw new RuntimeException("本局已结算，不可再截止下注");
        }
        if (round.getDrawLockedAt() != null) {
            throw new RuntimeException("已录入开彩，不可重新截止下注");
        }

        Instant previousCloseAt = round.getBetWindowCloseAt();
        Long previousCloseMessageId = round.getBetWindowCloseMessageId();
        boolean isRecutoff = previousCloseMessageId != null || previousCloseAt != null;

        SangongImMessage cutoffMessage = messageOrder.resolveCutoffMessage(round, untilMessageId, untilMsgSeq);
        long cutoffMessageId = cutoffMessage.getId();
        messageOrder.validateExcludeMessageIds(round, cutoffMessageId, excludeMessageIds);
        Instant closeAt = closeAtFromCutoffMessage(cutoffMessage);

        round.setBetWindowCloseMessageId(cutoffMessageId);
        round.setBetWindowCloseAt(closeAt);
        rounds.save(round);

        try {
            Map<String, Object> recutoffPrep = null;
            Map<String, Object> recallSummary = null;
            if (isRecutoff) {
                recutoffPrep = imMessages.prepareBetWindowRecutoff(reload(round), cutoffMessageId);
                recallSummary = im.recallBetSummaryMessages(reload(round));
            }
            imMessages.discardPendingAfterCutoff(reload(round), cutoffMessageId);
            Map<String, Object> manualExclusions = imMessages.applyManualExclusions(reload(round), excludeMessageIds);

            Map<String, Object> summary = imMessages.submitPendingBets(reload(round), excludeMessageIds);
            summary.put("isRecutoff", isRecutoff);
            summary.put("cutoffMessageId", cutoffMessageId);
            summary.put("excludeMessageIds", excludeMessageIds);
            summary.put("manualExclusions", manualExclusions);
            summary.put("cutoffMsgSeq", cutoffMessage.getMsgSeq());
            if (isRecutoff) {
                summary.put("previousCloseAt", TimeFmt.iso(previousCloseAt));
                summary.put("previousCloseMsgTime", previousCloseAt != null ? previousCloseAt.getEpochSecond() : null);
                summary.put("previousCutoffMessageId", previousCloseMessageId);
                summary.put("recutoff", recutoffPrep);
                summary.put("recallSummary", recallSummary);
            }
            realtime.touch();
            return summary;
        } catch (RuntimeException e) {
            round.setBetWindowCloseAt(isRecutoff ? previousCloseAt : null);
            round.setBetWindowCloseMessageId(isRecutoff ? previousCloseMessageId : null);
            rounds.save(round);
            throw e;
        }
    }

    /** 预览下注统计（不落注）。 */
    public Map<String, Object> previewBetWindow(SangongRound round, Long untilMessageId,
                                                Long untilMsgSeq, List<Long> excludeMessageIds) {
        if (round.getBetWindowOpenAt() == null) {
            throw new RuntimeException("尚未发送定庄，下注窗口未开启");
        }
        if (SangongRound.SETTLED.equals(round.getStatus())) {
            throw new RuntimeException("本局已结算，不可预览截止");
        }
        if (round.getDrawLockedAt() != null) {
            throw new RuntimeException("已录入开彩，不可预览重新截止");
        }

        Instant previousCloseAt = round.getBetWindowCloseAt();
        Long previousCloseMessageId = round.getBetWindowCloseMessageId();
        boolean isRecutoff = previousCloseMessageId != null || previousCloseAt != null;

        SangongImMessage cutoffMessage = messageOrder.resolveCutoffMessage(round, untilMessageId, untilMsgSeq);
        long cutoffMessageId = cutoffMessage.getId();
        messageOrder.validateExcludeMessageIds(round, cutoffMessageId, excludeMessageIds);
        Instant closeAt = closeAtFromCutoffMessage(cutoffMessage);

        Map<String, Object> report = isRecutoff
            ? imMessages.buildBetPreviewReportForRecutoff(round, cutoffMessageId, excludeMessageIds)
            : imMessages.buildBetPreviewReport(round, cutoffMessageId, excludeMessageIds);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roundId", round.getId());
        out.put("periodNo", round.getPeriodNo());
        out.put("windowOpenAt", TimeFmt.iso(round.getBetWindowOpenAt()));
        out.put("isRecutoff", isRecutoff);
        out.put("previousCloseAt", isRecutoff ? TimeFmt.iso(previousCloseAt) : null);
        out.put("previousCloseMsgTime", isRecutoff && previousCloseAt != null ? previousCloseAt.getEpochSecond() : null);
        out.put("previousCutoffMessageId", isRecutoff ? previousCloseMessageId : null);
        out.put("cutoffMessageId", cutoffMessageId);
        out.put("cutoffMsgSeq", cutoffMessage.getMsgSeq());
        out.put("excludeMessageIds", excludeMessageIds);
        out.put("previewCloseAt", TimeFmt.iso(closeAt));
        out.put("previewCloseMsgTime", closeAt.getEpochSecond());
        out.put("untilMessageId", untilMessageId);
        out.put("untilMsgSeq", untilMsgSeq);
        out.put("pendingMessageCount", report.getOrDefault("pendingMessageCount", 0));
        out.put("excludedAfterCutoff", imMessages.countPendingAfterCutoff(round, cutoffMessageId));
        out.put("report", report);
        return out;
    }

    private Instant closeAtFromCutoffMessage(SangongImMessage cutoffMessage) {
        if (cutoffMessage.getMsgTime() != null) {
            return Instant.ofEpochSecond(cutoffMessage.getMsgTime());
        }
        return cutoffMessage.getCreatedAt() != null ? cutoffMessage.getCreatedAt() : Instant.now();
    }

    public boolean sendCoBankNotify(SangongRound round) {
        Map<String, Object> summary = getCoBankSummary(round.getId());
        return im.notifyCoBankSummary(round, round.getPeriodNo(), summary);
    }

    private SangongRound reload(SangongRound round) {
        return rounds.findById(round.getId()).orElse(round);
    }
}
