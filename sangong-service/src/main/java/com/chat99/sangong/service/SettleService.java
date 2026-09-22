package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongBet;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongRoundDraw;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.BetRepository;
import com.chat99.sangong.repository.DrawRepository;
import com.chat99.sangong.repository.RoundRepository;
import com.chat99.sangong.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 结算：分账计划、结算落账、冲正与重结（数学与 PHP SettleService 完全一致）。 */
@Service
public class SettleService {
    private final CompareService compare;
    private final DrawService draws;
    private final RoundService rounds;
    private final BalanceService balance;
    private final GameSettingsService settings;
    private final UserService users;
    private final ImService im;
    private final BetService bets;
    private final BetRepository betRepo;
    private final DrawRepository drawRepo;
    private final RoundRepository roundRepo;
    private final UserRepository userRepo;
    private final RealtimeVersionStore realtime;
    private final ImMessageService imMessages;
    private final TransactionTemplate tx;
    private final RebateService rebates;
    private final TurnoverService turnover;

    @Autowired
    public SettleService(CompareService compare, DrawService draws, @Lazy RoundService rounds,
                         BalanceService balance, GameSettingsService settings, UserService users,
                         @Lazy ImService im, @Lazy BetService bets, BetRepository betRepo,
                         DrawRepository drawRepo, RoundRepository roundRepo, UserRepository userRepo,
                         RealtimeVersionStore realtime, @Lazy ImMessageService imMessages,
                         RebateService rebates, TurnoverService turnover,
                         org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txManager) {
        this.compare = compare;
        this.draws = draws;
        this.rounds = rounds;
        this.balance = balance;
        this.settings = settings;
        this.users = users;
        this.im = im;
        this.bets = bets;
        this.betRepo = betRepo;
        this.drawRepo = drawRepo;
        this.roundRepo = roundRepo;
        this.userRepo = userRepo;
        this.realtime = realtime;
        this.imMessages = imMessages;
        this.rebates = rebates;
        this.turnover = turnover;
        org.springframework.transaction.PlatformTransactionManager ptm = txManager.getIfAvailable();
        this.tx = ptm != null ? new TransactionTemplate(ptm) : null;
    }

    /** Backwards-compatible constructor for existing tests and integrations. */
    public SettleService(CompareService compare, DrawService draws, RoundService rounds,
                         BalanceService balance, GameSettingsService settings, UserService users,
                         ImService im, BetService bets, BetRepository betRepo,
                         DrawRepository drawRepo, RoundRepository roundRepo,
                         UserRepository userRepo, RealtimeVersionStore realtime,
                         ImMessageService imMessages, RebateService rebates,
                         org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txManager) {
        this(compare, draws, rounds, balance, settings, users, im, bets, betRepo,
                drawRepo, roundRepo, userRepo, realtime, imMessages, rebates, null, txManager);
    }

    /** 结算/冲正须原子落账；显式事务模板避免同类自调用绕过 @Transactional。 */
    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        if (tx == null) {
            return action.get();
        }
        return tx.execute(status -> action.get());
    }

    public Map<String, Object> settle(SangongRound round) {
        if (SangongRound.SETTLED.equals(round.getStatus())) {
            throw new RuntimeException("本局已结算");
        }
        Map<String, Object> drawStatus = draws.getStatus(round);
        if (!Boolean.TRUE.equals(drawStatus.get("complete"))) {
            @SuppressWarnings("unchecked")
            List<Integer> missing = (List<Integer>) drawStatus.getOrDefault("missingDoors", List.of());
            String missingText = missing.isEmpty() ? "部分门位"
                : missing.stream().map(d -> "门" + d).collect(Collectors.joining("、"));
            throw new RuntimeException("开彩未录满，请先录入：" + missingText);
        }
        if (round.getBankerDoor() == null) {
            throw new RuntimeException("尚未定庄，不可结算");
        }

        Map<String, Object> report = buildSettlementPlan(round);
        inTransaction(() -> {
            applySettlement(round.getId(), report, round.getSessionId());
            return null;
        });

        // 庄方流水按主庄/合庄成员的合庄金额比例累计；同一局只在结算成功后写入。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> settledBankers = (List<Map<String, Object>>) report.getOrDefault("bankers", List.of());
        for (Map<String, Object> banker : settledBankers) {
            long bankerId = ((Number) banker.getOrDefault("userId", 0)).longValue();
            long bankerTurnover = ((Number) banker.getOrDefault("betShare", 0)).longValue();
            if (bankerId > 0 && bankerTurnover > 0) {
                if (turnover != null) {
                    turnover.addBankerTurnover(bankerId, bankerTurnover, round.getId(), round.getSessionId());
                }
            }
        }

        SangongRound fresh = roundRepo.findById(round.getId()).orElse(round);
        Map<String, Object> freshReport = attachBalances(report, fresh);
        im.notifySettlementSummary(fresh.getPeriodNo(), freshReport);
        im.notifyTrendChart(fresh.getSessionId());
        im.notifyAdminSettleBill(fresh);
        realtime.touch();
        return freshReport;
    }

    private void applySettlement(long roundId, Map<String, Object> report, long sessionId) {
        SangongRound round = roundRepo.lockById(roundId);
        if (round == null || SangongRound.SETTLED.equals(round.getStatus())) {
            throw new RuntimeException("本局已结算");
        }
        for (Map<String, Object> item : creditList(report, "playerCredits")) {
            SangongUser user = userRepo.lockById(((Number) item.get("userId")).longValue());
            if (user == null) continue;
            balance.credit(user, ((Number) item.get("payout")).longValue(), "settle_win", sessionId,
                String.valueOf(item.get("note")), "round", round.getId(), null);
        }
        for (Map<String, Object> item : creditList(report, "bankerCredits")) {
            SangongUser user = userRepo.lockById(((Number) item.get("userId")).longValue());
            if (user == null) continue;
            long delta = ((Number) item.get("delta")).longValue();
            if (delta == 0) continue;
            if (delta > 0) {
                balance.credit(user, delta, "settle_banker", sessionId,
                    String.valueOf(item.get("note")), "round", round.getId(), null);
            } else {
                balance.debit(user, -delta, "settle_banker", sessionId,
                    String.valueOf(item.get("note")), "round", round.getId(), null);
            }
        }
        round.setStatus(SangongRound.SETTLED);
        round.setSettledAt(Instant.now());
        roundRepo.save(round);
        imMessages.clearRoundPendingEntries(round, "settled");
    }

    /** 冲正本局结算：反向回滚余额并清空开彩，仅允许下一局尚未开始。 */
    public Map<String, Object> voidSettlement(SangongRound round) {
        if (!SangongRound.SETTLED.equals(round.getStatus())) {
            throw new RuntimeException("本局未结算，无需冲正");
        }
        assertResettleAllowed(round);

        Map<String, Object> report = buildSettlementPlan(round);
        long sessionId = round.getSessionId();
        int periodNo = round.getPeriodNo();
        List<Map<String, Object>> voided = inTransaction(() -> applyVoid(round.getId(), report, sessionId, periodNo));

        if (turnover != null) {
            turnover.reverseRound(round.getId());
        }

        im.notifySettlementVoided(periodNo);
        realtime.touch();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roundId", round.getId());
        out.put("periodNo", periodNo);
        out.put("voided", voided);
        return out;
    }

    private List<Map<String, Object>> applyVoid(long roundId, Map<String, Object> report,
                                                long sessionId, int periodNo) {
        SangongRound round = roundRepo.lockById(roundId);
        if (round == null || !SangongRound.SETTLED.equals(round.getStatus())) {
            throw new RuntimeException("本局未结算，无需冲正");
        }
        assertResettleAllowed(round);
        String note = "第" + periodNo + "期结算冲正";
        List<Map<String, Object>> voided = new ArrayList<>();

        for (Map<String, Object> item : creditList(report, "playerCredits")) {
            long userId = ((Number) item.get("userId")).longValue();
            long payout = ((Number) item.get("payout")).longValue();
            if (payout <= 0) continue;
            SangongUser user = userRepo.lockById(userId);
            if (user == null) continue;
            balance.debit(user, payout, "settle_void", sessionId, note, "round", round.getId(), null);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("userId", userId);
            v.put("delta", -payout);
            v.put("role", "player");
            voided.add(v);
        }
        for (Map<String, Object> item : creditList(report, "bankerCredits")) {
            long userId = ((Number) item.get("userId")).longValue();
            long delta = ((Number) item.get("delta")).longValue();
            if (delta == 0) continue;
            SangongUser user = userRepo.lockById(userId);
            if (user == null) continue;
            if (delta > 0) {
                balance.debit(user, delta, "settle_void", sessionId, note, "round", round.getId(), null);
            } else {
                balance.credit(user, -delta, "settle_void", sessionId, note, "round", round.getId(), null);
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("userId", userId);
            v.put("delta", -delta);
            v.put("role", "banker");
            voided.add(v);
        }

        // 退回可录开彩状态；清空开彩强制重录；保留关窗，防冲正后继续下注
        drawRepo.deleteByRound(round.getId());
        round.setDrawLockedAt(null);
        round.setStatus(round.getCoBankClosedAt() != null
            ? SangongRound.CO_BANK_CLOSED : SangongRound.BETTING);
        round.setSettledAt(null);
        roundRepo.save(round);
        return voided;
    }

    /** 冲正后必须重新录入开彩，再立即结算。 */
    public Map<String, Object> resettle(SangongRound round, Map<Integer, String> drawInputs) {
        if (drawInputs == null || drawInputs.isEmpty()) {
            throw new RuntimeException("冲正重结须重新录入开奖号码");
        }
        Map<String, Object> voidInfo = voidSettlement(round);
        SangongRound fresh = roundRepo.findById(round.getId())
            .orElseThrow(() -> new RuntimeException("局不存在"));
        draws.recordDraws(fresh, drawInputs);
        Map<String, Object> settlement = settle(roundRepo.findById(fresh.getId()).orElse(fresh));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("voided", voidInfo);
        out.put("settlement", settlement);
        return out;
    }

    /** 下一局已开（同会话存在更大期号）则禁止冲正。 */
    public void assertResettleAllowed(SangongRound round) {
        if (roundRepo.existsLaterRound(round.getSessionId(), round.getPeriodNo())) {
            throw new RuntimeException("下一局已开始，不可冲正重结");
        }
    }

    /** 已结算局的完整报表（供会话累计账单等复用）。 */
    public Map<String, Object> buildReportForRound(SangongRound round) {
        if (!SangongRound.SETTLED.equals(round.getStatus())) {
            throw new RuntimeException("须已结算的局");
        }
        return attachBalances(buildSettlementPlan(round), round);
    }

    /** 分账计划（核心数学，与 PHP buildSettlementPlan 逐行对齐）。 */
    public Map<String, Object> buildSettlementPlan(SangongRound round) {
        int bankerDoor = round.getBankerDoor();
        Map<Integer, SangongRoundDraw> drawRows = new LinkedHashMap<>();
        for (SangongRoundDraw d : drawRepo.listByRound(round.getId())) {
            drawRows.put(d.getDoor(), d);
        }
        SangongRoundDraw bankerDraw = drawRows.get(bankerDoor);
        if (bankerDraw == null) {
            throw new RuntimeException("庄门开彩未录入");
        }

        List<SangongBet> betList = betRepo.listByRound(round.getId());
        Map<Integer, Long> doorTotals = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> playerDoorResults = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> playerSummaries = new LinkedHashMap<>();
        long totalIdleBets = 0;
        long bankerGross = 0;

        for (SangongBet bet : betList) {
            int door = bet.getDoor();
            if (door == bankerDoor) continue;

            long amount = bet.getAmount();
            long userId = bet.getUserId();
            totalIdleBets += amount;
            doorTotals.merge(door, amount, Long::sum);

            Map<String, Object> doorResult = playerDoorResults.get(door);
            if (doorResult == null) {
                SangongRoundDraw playerDraw = drawRows.get(door);
                if (playerDraw == null) {
                    throw new RuntimeException("门" + door + "开彩未录入");
                }
                boolean bankerWins = compare.bankerWins(bankerDraw, playerDraw);
                doorResult = new LinkedHashMap<>();
                doorResult.put("door", door);
                doorResult.put("bankerWins", bankerWins);
                doorResult.put("playerWins", !bankerWins);
                doorResult.put("amount", playerDraw.amountDisplay());
                doorResult.put("handLabel", playerDraw.getHandLabel());
                playerDoorResults.put(door, doorResult);
            }

            Map<String, Object> summary = playerSummaries.get(userId);
            if (summary == null) {
                SangongUser user = users.findById(userId);
                summary = new LinkedHashMap<>();
                summary.put("userId", userId);
                summary.put("nickname", user != null ? users.resolveNickname(user) : "");
                summary.put("totalBet", 0L);
                summary.put("totalWin", 0L);
                summary.put("totalLoss", 0L);
                summary.put("net", 0L);
                summary.put("doors", new ArrayList<Map<String, Object>>());
                playerSummaries.put(userId, summary);
            }

            boolean playerWins = Boolean.TRUE.equals(doorResult.get("playerWins"));
            long net = playerWins ? amount : -amount;
            summary.put("totalBet", (Long) summary.get("totalBet") + amount);
            if (net > 0) {
                summary.put("totalWin", (Long) summary.get("totalWin") + net);
            } else {
                summary.put("totalLoss", (Long) summary.get("totalLoss") - net);
            }
            summary.put("net", (Long) summary.get("net") + net);

            Map<String, Object> doorRow = new LinkedHashMap<>();
            doorRow.put("door", door);
            doorRow.put("bet", amount);
            doorRow.put("compare", playerWins ? "闲赢" : "庄赢");
            doorRow.put("net", net);
            doorRow.put("amount", doorResult.get("amount"));
            doorRow.put("handLabel", doorResult.get("handLabel"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> doorRows = (List<Map<String, Object>>) summary.get("doors");
            doorRows.add(doorRow);

            if (Boolean.TRUE.equals(doorResult.get("bankerWins"))) {
                bankerGross += amount;
            } else {
                bankerGross -= amount;
            }
        }

        int rakePercent = settings.getBankerRakePercent();
        long rake = Math.floorDiv(totalIdleBets * rakePercent, 100L);
        long bankerNet = bankerGross - rake;

        Map<String, Object> coBank = rounds.getCoBankSummary(round.getId());
        long bankerUserId = round.getBankerUserId() != null ? round.getBankerUserId() : 0;
        List<Map<String, Object>> bankerCredits = distributeBankerNet(coBank, bankerNet, bankerUserId);

        List<Map<String, Object>> playerCredits = new ArrayList<>();
        for (Map<String, Object> summary : playerSummaries.values()) {
            long payout = 0;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> doorRows = (List<Map<String, Object>>) summary.get("doors");
            for (Map<String, Object> doorRow : doorRows) {
                if (((Number) doorRow.get("net")).longValue() > 0) {
                    payout += 2 * ((Number) doorRow.get("bet")).longValue();
                }
            }
            if (payout <= 0) continue;
            Map<String, Object> credit = new LinkedHashMap<>();
            credit.put("userId", summary.get("userId"));
            credit.put("payout", payout);
            credit.put("note", "第" + round.getPeriodNo() + "期结算赢回本金2倍");
            playerCredits.add(credit);
        }

        List<Map<String, Object>> doorCompare = new ArrayList<>();
        int doorCount = settings.getDoorCount();
        for (int door = 1; door <= doorCount; door++) {
            SangongRoundDraw draw = drawRows.get(door);
            long doorBet = doorTotals.getOrDefault(door, 0L);
            boolean isBanker = door == bankerDoor;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("door", door);
            item.put("role", isBanker ? "banker" : "player");
            item.put("amount", draw != null ? draw.amountDisplay() : null);
            item.put("handLabel", draw != null ? draw.getHandLabel() : null);
            item.put("hasBets", doorBet > 0);
            item.put("doorBets", doorBet);
            item.put("bankerGross", 0L);
            item.put("compare", null);
            if (!isBanker && draw != null) {
                boolean bankerWins = compare.bankerWins(bankerDraw, draw);
                item.put("compare", bankerWins ? "庄赢" : "闲赢");
                if (doorBet > 0) {
                    item.put("bankerGross", bankerWins ? doorBet : -doorBet);
                }
            }
            doorCompare.add(item);
        }

        long bankerCreditsSum = bankerCredits.stream()
            .mapToLong(c -> ((Number) c.get("delta")).longValue()).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roundId", round.getId());
        out.put("periodNo", round.getPeriodNo());
        out.put("bankerUserId", bankerUserId);
        out.put("bankerDoor", bankerDoor);
        Map<String, Object> bankerHand = new LinkedHashMap<>();
        bankerHand.put("amount", bankerDraw.amountDisplay());
        bankerHand.put("handLabel", bankerDraw.getHandLabel());
        out.put("bankerHand", bankerHand);
        out.put("totalIdleBets", totalIdleBets);
        out.put("bankerGross", bankerGross);
        out.put("rakePercent", rakePercent);
        out.put("rake", rake);
        out.put("bankerNet", bankerNet);
        out.put("systemRemainder", bankerNet - bankerCreditsSum);
        out.put("doorCompare", doorCompare);
        out.put("players", new ArrayList<>(playerSummaries.values()));
        out.put("bankers", formatBankerRows(coBank, bankerCredits, bankerUserId));
        out.put("playerCredits", playerCredits);
        out.put("bankerCredits", bankerCredits);
        return out;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> distributeBankerNet(Map<String, Object> coBank, long bankerNet, long bankerUserId) {
        long pool = ((Number) coBank.getOrDefault("poolTotal", 0)).longValue();
        List<Map<String, Object>> members = (List<Map<String, Object>>) coBank.getOrDefault("members", List.of());

        if (members.isEmpty()) {
            if (bankerUserId <= 0) {
                throw new RuntimeException("无庄方成员");
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("userId", bankerUserId);
            c.put("delta", bankerNet);
            c.put("note", "庄方本局净结果");
            return List.of(c);
        }
        if (pool <= 0) {
            Map<String, Object> first = members.get(0);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("userId", ((Number) first.get("userId")).longValue());
            c.put("delta", bankerNet);
            c.put("note", "庄方本局净结果");
            return List.of(c);
        }
        List<Map<String, Object>> credits = new ArrayList<>();
        for (Map<String, Object> member : members) {
            long amount = ((Number) member.get("amount")).longValue();
            long share = Math.floorDiv(bankerNet * amount, pool);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("userId", ((Number) member.get("userId")).longValue());
            c.put("delta", share);
            c.put("note", "庄方本局净结果分摊");
            credits.add(c);
        }
        return credits;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> formatBankerRows(Map<String, Object> coBank,
                                                       List<Map<String, Object>> bankerCredits,
                                                       long bankerUserId) {
        Map<Long, Long> deltaMap = new LinkedHashMap<>();
        for (Map<String, Object> item : bankerCredits) {
            deltaMap.put(((Number) item.get("userId")).longValue(), ((Number) item.get("delta")).longValue());
        }
        List<Map<String, Object>> members = (List<Map<String, Object>>) coBank.getOrDefault("members", List.of());
        if (members.isEmpty() && bankerUserId > 0) {
            SangongUser user = users.findById(bankerUserId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", bankerUserId);
            row.put("nickname", user != null ? users.resolveNickname(user) : "");
            row.put("amount", 0L);
            row.put("sharePercent", 100.0);
            row.put("net", deltaMap.getOrDefault(bankerUserId, 0L));
            return List.of(row);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> member : members) {
            long userId = ((Number) member.get("userId")).longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", userId);
            row.put("nickname", member.get("nickname"));
            row.put("amount", ((Number) member.get("amount")).longValue());
            row.put("sharePercent", ((Number) member.getOrDefault("sharePercent", 0)).doubleValue());
            row.put("net", deltaMap.getOrDefault(userId, 0L));
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attachBalances(Map<String, Object> report, SangongRound round) {
        Map<String, Object> betReport = bets.getRoundBetReport(round);
        Map<Long, Map<Integer, Long>> doorBetsByUserId = new LinkedHashMap<>();
        for (Object o : (List<Object>) betReport.getOrDefault("users", List.of())) {
            Map<String, Object> userRow = (Map<String, Object>) o;
            long userId = userRow.get("userId") instanceof Number n ? n.longValue() : 0;
            if (userId <= 0) continue;
            doorBetsByUserId.put(userId, (Map<Integer, Long>) userRow.getOrDefault("doors", Map.of()));
        }

        for (Map<String, Object> player : (List<Map<String, Object>>) report.getOrDefault("players", List.of())) {
            long userId = ((Number) player.get("userId")).longValue();
            SangongUser user = users.findById(userId);
            long net = ((Number) player.getOrDefault("net", 0)).longValue();
            Long balanceAfter = user != null ? user.getBalance() : null;
            player.put("balanceAfter", balanceAfter);
            player.put("balanceBefore", balanceAfter != null ? balanceAfter - net : null);
            player.put("imUserId", user != null ? user.getImUserId() : null);
            player.put("doorBets", doorBetsByUserId.getOrDefault(userId, Map.of()));
        }

        Map<String, Object> coBank = rounds.getCoBankSummary(round.getId());
        long coBankPool = ((Number) coBank.getOrDefault("poolTotal", 0)).longValue();
        long totalIdleBets = ((Number) report.getOrDefault("totalIdleBets", 0)).longValue();
        long rake = ((Number) report.getOrDefault("rake", 0)).longValue();
        report.put("coBankCount", ((Number) coBank.getOrDefault("coBankerCount", 0)).intValue());

        long mainBankerNet = 0;
        long roundBankerUserId = round.getBankerUserId() != null ? round.getBankerUserId() : 0;
        for (Map<String, Object> banker : (List<Map<String, Object>>) report.getOrDefault("bankers", List.of())) {
            long userId = ((Number) banker.get("userId")).longValue();
            SangongUser user = users.findById(userId);
            long net = ((Number) banker.getOrDefault("net", 0)).longValue();
            Long balanceAfter = user != null ? user.getBalance() : null;
            banker.put("balanceAfter", balanceAfter);
            banker.put("balanceBefore", balanceAfter != null ? balanceAfter - net : null);
            banker.put("imUserId", user != null ? user.getImUserId() : null);
            long memberAmount = ((Number) banker.getOrDefault("amount", 0)).longValue();
            if (coBankPool > 0) {
                banker.put("betShare", Math.floorDiv(totalIdleBets * memberAmount, coBankPool));
                banker.put("rakeShare", Math.floorDiv(rake * memberAmount, coBankPool));
            } else {
                banker.put("betShare", totalIdleBets);
                banker.put("rakeShare", rake);
            }
            if (userId == roundBankerUserId) {
                mainBankerNet = net;
            }
        }

        int doorCount = settings.getDoorCount();
        Map<Integer, Long> reportDoorTotals = new LinkedHashMap<>();
        Map<Integer, Long> betReportTotals = (Map<Integer, Long>) betReport.getOrDefault("doorTotals", Map.of());
        for (int door = 1; door <= doorCount; door++) {
            reportDoorTotals.put(door, betReportTotals.getOrDefault(door, 0L));
        }

        long eat = 0;
        long pay = 0;
        Map<Integer, String> doorMultipliers = new LinkedHashMap<>();
        Map<Integer, String> doorAmounts = new LinkedHashMap<>();
        Map<Integer, String> doorPoints = new LinkedHashMap<>();
        for (Map<String, Object> door : (List<Map<String, Object>>) report.getOrDefault("doorCompare", List.of())) {
            int d = ((Number) door.getOrDefault("door", 0)).intValue();
            if (d <= 0) continue;
            Object amount = door.get("amount");
            doorPoints.put(d, amount == null ? "" : String.valueOf(amount));
            if ("banker".equals(door.get("role"))) {
                doorMultipliers.put(d, "");
                doorAmounts.put(d, "");
                continue;
            }
            long doorBet = ((Number) door.getOrDefault("doorBets", 0)).longValue();
            long gross = ((Number) door.getOrDefault("bankerGross", 0)).longValue();
            doorAmounts.put(d, doorBet > 0 ? String.valueOf(gross) : "");
            if (doorBet <= 0) {
                doorMultipliers.put(d, "");
                continue;
            }
            String cmp = door.get("compare") != null ? String.valueOf(door.get("compare")) : "";
            if ("庄赢".equals(cmp)) {
                doorMultipliers.put(d, "1.0");
                eat += doorBet;
            } else if ("闲赢".equals(cmp)) {
                doorMultipliers.put(d, "-1.0");
                pay += doorBet;
            } else {
                doorMultipliers.put(d, "");
            }
        }

        SangongUser bankerUser = roundBankerUserId > 0 ? users.findById(roundBankerUserId) : null;
        report.put("doorCount", doorCount);
        report.put("doorTotals", reportDoorTotals);
        report.put("grandTotal", ((Number) betReport.getOrDefault("grandTotal",
            reportDoorTotals.values().stream().mapToLong(Long::longValue).sum())).longValue());
        report.put("bankerEat", eat);
        report.put("bankerPay", pay);
        report.put("bankerNickname", bankerUser != null ? users.resolveNickname(bankerUser) : "");
        report.put("bankerImUserId", bankerUser != null ? bankerUser.getImUserId() : null);
        report.put("mainBankerNet", mainBankerNet);
        Long bankerBalanceAfter = bankerUser != null ? bankerUser.getBalance() : null;
        report.put("bankerBalanceAfter", bankerBalanceAfter);
        report.put("bankerBalanceBefore", bankerBalanceAfter != null ? bankerBalanceAfter - mainBankerNet : null);
        report.put("doorMultipliers", doorMultipliers);
        report.put("doorAmounts", doorAmounts);
        report.put("doorPoints", doorPoints);
        return report;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> creditList(Map<String, Object> report, String key) {
        return (List<Map<String, Object>>) report.getOrDefault(key, List.of());
    }
}
