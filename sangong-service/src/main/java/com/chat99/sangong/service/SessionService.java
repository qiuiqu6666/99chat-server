package com.chat99.sangong.service;

import com.chat99.sangong.common.TimeFmt;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongSession;
import com.chat99.sangong.repository.RoundRepository;
import com.chat99.sangong.repository.SessionRepository;
import com.chat99.sangong.repository.UserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 开机/关机与会话状态（与 PHP SessionService 一致）。 */
@Service
public class SessionService {
    private final RoundService rounds;
    private final ImService im;
    private final SessionRepository sessions;
    private final RoundRepository roundRepo;
    private final RealtimeVersionStore realtime;
    private final UserRepository users;
    private final RebateClaimService rebateClaims;

    public SessionService(@Lazy RoundService rounds, @Lazy ImService im,
                          SessionRepository sessions, RoundRepository roundRepo,
                          RealtimeVersionStore realtime, UserRepository users,
                          RebateClaimService rebateClaims) {
        this.rounds = rounds;
        this.im = im;
        this.sessions = sessions;
        this.roundRepo = roundRepo;
        this.realtime = realtime;
        this.users = users;
        this.rebateClaims = rebateClaims;
    }

    public SangongSession getRunning() {
        return sessions.findRunning().orElse(null);
    }

    public Map<String, Object> getStatus() {
        SangongSession session = getRunning();
        Map<String, Object> out = new LinkedHashMap<>();
        if (session == null) {
            out.put("status", SangongSession.IDLE);
            out.put("session", null);
            out.put("round", null);
            return out;
        }
        SangongRound round = session.getCurrentRoundId() != null
            ? roundRepo.findById(session.getCurrentRoundId()).orElse(null) : null;
        out.put("status", session.getStatus());
        out.put("session", formatSession(session));
        out.put("round", round != null ? rounds.formatRound(round) : null);
        return out;
    }

    /** 开机：新会话，游戏期数从 1 开始。 */
    @Transactional
    public SangongSession start() {
        if (sessions.findRunningForUpdate().isPresent()) {
            throw new RuntimeException("已在运行中，不可重复开机");
        }
        SangongSession session = sessions.insertRunning();
        SangongRound round = rounds.createForSession(session, 1);
        session.setCurrentRoundId(round.getId());
        sessions.update(session);
        realtime.touch();
        return sessions.findById(session.getId()).orElse(session);
    }

    @Transactional
    public Map<String, Object> stop() {
        SangongSession session = getRunning();
        if (session == null) {
            throw new RuntimeException("当前未开机");
        }
        SangongRound round = session.getCurrentRoundId() != null
            ? roundRepo.findById(session.getCurrentRoundId()).orElse(null) : null;

        Map<String, Object> voidedRound = null;
        if (round != null && !SangongRound.SETTLED.equals(round.getStatus())) {
            voidedRound = rounds.voidUnsettledRound(round);
        }

        long rebateTotal = 0L;
        long agentRebateTotal = 0L;
        int rebateUserCount = 0;
        for (com.chat99.sangong.domain.SangongUser user : users.listAll()) {
            Map<String, Object> claimed = rebateClaims.claimPlayerRebate(user, session.getId(), "CLOSE");
            long playerAmount = ((Number) claimed.getOrDefault("amount", 0L)).longValue();
            long agentAmount = ((Number) claimed.getOrDefault("agentAmount", 0L)).longValue();
            if (playerAmount > 0 || agentAmount > 0) rebateUserCount++;
            rebateTotal += playerAmount;
            agentRebateTotal += agentAmount;
        }

        SangongRound lastSettledRound = roundRepo.findLastSettledBySession(session.getId()).orElse(null);
        if (lastSettledRound != null) {
            im.notifyAdminSettleBill(lastSettledRound);
        }

        session.setStatus(SangongSession.IDLE);
        session.setStoppedAt(Instant.now());
        session.setCurrentRoundId(null);
        session.setCurrentPeriodNo(0);
        sessions.update(session);
        realtime.touch();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sessions.findById(session.getId()).orElse(session));
        out.put("voidedRound", voidedRound);
        out.put("rebateUserCount", rebateUserCount);
        out.put("rebateTotal", rebateTotal);
        out.put("agentRebateTotal", agentRebateTotal);
        return out;
    }

    public Map<String, Object> formatSession(SangongSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", session.getId());
        out.put("status", session.getStatus());
        out.put("periodNo", session.getCurrentPeriodNo());
        out.put("currentRoundId", session.getCurrentRoundId());
        out.put("startedAt", TimeFmt.iso(session.getStartedAt()));
        out.put("stoppedAt", TimeFmt.iso(session.getStoppedAt()));
        out.put("businessDate", session.getBusinessDate());
        out.put("batchNo", session.getBatchNo());
        return out;
    }

    public java.util.List<Map<String, Object>> listRecent(int limit) {
        return sessions.listRecent(limit).stream().map(this::formatSession).toList();
    }

    public SangongSession findBatch(long sessionId) {
        return sessions.findById(sessionId).orElseThrow(() -> new RuntimeException("业务批次不存在"));
    }

    public SangongSession findBatch(String batchNo) {
        return sessions.findByBatchNo(batchNo).orElseThrow(() -> new RuntimeException("业务批次不存在"));
    }

    public SangongSession getLatest() {
        return sessions.findLatest().orElse(null);
    }
}
