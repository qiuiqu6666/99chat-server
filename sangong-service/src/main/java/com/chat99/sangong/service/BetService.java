package com.chat99.sangong.service;

import com.chat99.sangong.common.InsufficientBalanceException;
import com.chat99.sangong.domain.SangongBet;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.BetRepository;
import com.chat99.sangong.repository.RoundRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 下注：校验、扣款落注、撤销与统计（与 PHP BetService 一致）。 */
@Service
public class BetService {
    private final RoundService rounds;
    private final BalanceService balance;
    private final ImService im;
    private final GameSettingsService gameSettings;
    private final UserService users;
    private final BetRepository bets;
    private final RoundRepository roundRepo;
    private final RealtimeVersionStore realtime;
    private final TurnoverService turnover;

    public BetService(RoundService rounds, BalanceService balance, ImService im,
                      GameSettingsService gameSettings, UserService users,
                      BetRepository bets, RoundRepository roundRepo, RealtimeVersionStore realtime,
                      TurnoverService turnover) {
        this.rounds = rounds;
        this.balance = balance;
        this.im = im;
        this.gameSettings = gameSettings;
        this.users = users;
        this.bets = bets;
        this.roundRepo = roundRepo;
        this.realtime = realtime;
        this.turnover = turnover;
    }

    public Map<String, Object> placeBet(SangongUser user, int door, long amount, boolean isProxy) {
        return placeBet(user, door, amount, isProxy, null, true);
    }

    @Transactional
    public Map<String, Object> placeBet(SangongUser user, int door, long amount, boolean isProxy,
                                        Long imMessageId, boolean notify) {
        SangongRound round = rounds.getCurrent();
        if (round == null) {
            reject(user, "当前未开机或无可下注局");
            throw new RuntimeException("当前不可下注");
        }
        if (!round.allowsBetting()) {
            String reason = phaseRejectReason(round);
            reject(user, reason);
            throw new RuntimeException(reason);
        }
        if (door < 1 || amount <= 0) {
            reject(user, "门号与金额须为正整数");
            throw new RuntimeException("下注参数无效");
        }
        String doorError = gameSettings.validateDoor(door);
        if (doorError != null) {
            reject(user, doorError);
            throw new RuntimeException(doorError);
        }
        String betError = gameSettings.validateBetAmount(amount);
        if (betError != null) {
            reject(user, betError);
            throw new RuntimeException(betError);
        }
        if (round.getBankerDoor() != null && door == round.getBankerDoor()) {
            reject(user, "庄门不可下注");
            throw new RuntimeException("庄门不可下注");
        }
        if (round.getBankerUserId() != null && round.getBankerUserId() == user.getId()) {
            reject(user, "庄主不可在闲门下注");
            throw new RuntimeException("庄主不可下注");
        }
        if (!balance.hasEnough(user, amount)) {
            long bal = balance.getBalance(user);
            if (!isProxy && notify) {
                String displayName = users.resolveNickname(user);
                im.notifyInsufficientBalance(user, displayName, door, amount, bal);
            }
            throw new InsufficientBalanceException(bal);
        }

        long sessionId = round.getSessionId();
        SangongUser fresh = balance.reserveForBet(user, amount, sessionId);
        long betId = bets.insert(round.getId(), fresh.getId(), door, amount, isProxy, imMessageId);
        turnover.addPlayerTurnover(fresh.getId(), amount, sessionId);
        long doorTotal = bets.sumDoorAmount(round.getId(), door);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", fresh);
        result.put("door", door);
        result.put("amount", amount);
        result.put("doorTotal", doorTotal);
        result.put("balance", fresh.getBalance());
        result.put("roundId", round.getId());
        result.put("periodNo", round.getPeriodNo());
        result.put("betId", betId);

        if (notify) {
            String displayName = users.resolveNickname(fresh);
            im.notifyBetSuccess(fresh, displayName, door, amount, doorTotal, fresh.getBalance());
        }
        return result;
    }

    @Transactional
    public Map<String, Object> placeMultiBet(SangongUser user, List<Integer> doors, long amount,
                                             boolean isProxy, Long imMessageId, boolean notify,
                                             boolean allIdle, String allIdleKeyword) {
        doors = new ArrayList<>(doors);
        if (doors.isEmpty() || amount <= 0) {
            throw new RuntimeException("下注参数无效");
        }
        for (int door : doors) {
            Map<String, Object> check = validateBetCommand(user, door, amount, false, 0);
            if (!Boolean.TRUE.equals(check.get("ok"))) {
                String message = String.valueOf(check.getOrDefault("message", "下注校验未通过"));
                if (notify) {
                    im.notifyBetRejected(user, users.resolveNickname(user), message);
                }
                throw new RuntimeException(message);
            }
        }
        long totalAmount = amount * doors.size();
        long bal = balance.getBalance(user);
        if (bal < totalAmount) {
            if (!isProxy && notify) {
                im.notifyInsufficientBalance(user, users.resolveNickname(user), doors.get(0), totalAmount, bal);
            }
            throw new InsufficientBalanceException(bal);
        }

        List<Long> betIds = new ArrayList<>();
        Map<String, Object> lastResult = null;
        for (int door : doors) {
            lastResult = placeBet(user, door, amount, isProxy, imMessageId, false);
            betIds.add(((Number) lastResult.get("betId")).longValue());
            user = (SangongUser) lastResult.get("user");
        }

        if (notify && lastResult != null) {
            im.notifyMultiBetSuccess(user, users.resolveNickname(user), doors, amount, totalAmount,
                ((Number) lastResult.get("balance")).longValue(), allIdle, allIdleKeyword);
        }
        realtime.touch();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doors", doors);
        out.put("amount", amount);
        out.put("totalAmount", totalAmount);
        out.put("balance", ((Number) lastResult.get("balance")).longValue());
        out.put("roundId", ((Number) lastResult.get("roundId")).longValue());
        out.put("periodNo", ((Number) lastResult.get("periodNo")).intValue());
        out.put("betIds", betIds);
        out.put("betId", betIds.get(0));
        return out;
    }

    /** 窗口内仅校验指令与余额，不扣款、不落注。 */
    public Map<String, Object> validateMultiBetCommand(SangongUser user, List<Integer> doors,
                                                       long amount, boolean notify, long reservedAmount) {
        if (doors.isEmpty() || amount <= 0) {
            return validationFail(user, "门号与金额须为正整数", "INVALID_BET", notify);
        }
        for (int door : doors) {
            Map<String, Object> check = validateBetCommand(user, door, amount, false, 0);
            if (!Boolean.TRUE.equals(check.get("ok"))) {
                if (notify) {
                    im.notifyBetRejected(user, users.resolveNickname(user),
                        String.valueOf(check.getOrDefault("message", "下注校验未通过")));
                }
                return check;
            }
        }
        SangongRound round = rounds.getCurrent();
        if (round == null) {
            return validationFail(user, "当前未开机或无可下注局", "NO_ROUND", notify);
        }
        long bal = balance.getBalance(user);
        reservedAmount = Math.max(0, reservedAmount);
        long totalAmount = amount * doors.size();
        long required = totalAmount + reservedAmount;
        Map<String, Object> out = new LinkedHashMap<>();
        if (bal < required) {
            if (notify) {
                im.notifyInsufficientBalance(user, users.resolveNickname(user), doors.get(0), totalAmount, bal);
            }
            out.put("ok", false);
            out.put("code", "INSUFFICIENT_BALANCE");
            out.put("message", ImService.INSUFFICIENT_BALANCE_NOTICE);
            out.put("balance", bal);
            out.put("availableBalance", Math.max(0, bal - reservedAmount));
            out.put("doors", doors);
            out.put("amount", amount);
            out.put("totalAmount", totalAmount);
            out.put("door", doors.get(0));
            return out;
        }
        out.put("ok", true);
        out.put("balance", bal);
        out.put("availableBalance", bal - reservedAmount - totalAmount);
        out.put("doors", doors);
        out.put("amount", amount);
        out.put("totalAmount", totalAmount);
        out.put("door", doors.get(0));
        return out;
    }

    public Map<String, Object> validateBetCommand(SangongUser user, int door, long amount,
                                                  boolean notify, long reservedAmount) {
        SangongRound round = rounds.getCurrent();
        if (round == null) {
            return validationFail(user, "当前未开机或无可下注局", "NO_ROUND", notify);
        }
        if (!round.allowsBetting()) {
            return validationFail(user, phaseRejectReason(round), "BET_REJECTED", notify);
        }
        if (door < 1 || amount <= 0) {
            return validationFail(user, "门号与金额须为正整数", "INVALID_BET", notify);
        }
        String doorError = gameSettings.validateDoor(door);
        if (doorError != null) {
            return validationFail(user, doorError, "BET_REJECTED", notify);
        }
        String betError = gameSettings.validateBetAmount(amount);
        if (betError != null) {
            return validationFail(user, betError, "BET_REJECTED", notify);
        }
        if (round.getBankerDoor() != null && door == round.getBankerDoor()) {
            return validationFail(user, "庄门不可下注", "BET_REJECTED", notify);
        }
        if (round.getBankerUserId() != null && round.getBankerUserId() == user.getId()) {
            return validationFail(user, "庄主不可在闲门下注", "BET_REJECTED", notify);
        }
        long bal = balance.getBalance(user);
        reservedAmount = Math.max(0, reservedAmount);
        long required = amount + reservedAmount;
        Map<String, Object> out = new LinkedHashMap<>();
        if (bal < required) {
            if (notify) {
                im.notifyInsufficientBalance(user, users.resolveNickname(user), door, amount, bal);
            }
            out.put("ok", false);
            out.put("code", "INSUFFICIENT_BALANCE");
            out.put("message", ImService.INSUFFICIENT_BALANCE_NOTICE);
            out.put("balance", bal);
            out.put("availableBalance", Math.max(0, bal - reservedAmount));
            out.put("door", door);
            out.put("amount", amount);
            return out;
        }
        out.put("ok", true);
        out.put("balance", bal);
        out.put("availableBalance", bal - reservedAmount - amount);
        out.put("door", door);
        out.put("amount", amount);
        return out;
    }

    private Map<String, Object> validationFail(SangongUser user, String message, String code, boolean notify) {
        if (notify) {
            im.notifyBetRejected(user, users.resolveNickname(user), message);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("code", code);
        out.put("message", message);
        return out;
    }

    /** 撤回消息撤销单笔下注。 */
    @Transactional
    public Map<String, Object> cancelBetByRecall(long betId) {
        Map<String, Object> out = new LinkedHashMap<>();
        SangongBet bet = bets.findById(betId).orElse(null);
        if (bet == null) {
            out.put("cancelled", false);
            out.put("reason", "bet_not_found");
            return out;
        }
        SangongRound round = roundRepo.findById(bet.getRoundId()).orElse(null);
        if (round == null) {
            out.put("cancelled", false);
            out.put("reason", "round_not_found");
            return out;
        }
        if (!round.allowsBetting()) {
            out.put("cancelled", false);
            out.put("reason", "round_not_betting");
            return out;
        }
        SangongUser user = users.findById(bet.getUserId());
        if (user == null) {
            out.put("cancelled", false);
            out.put("reason", "user_not_found");
            return out;
        }
        int door = bet.getDoor();
        long amount = bet.getAmount();
        bets.delete(bet.getId());
        user = balance.releaseHold(user, amount, "bet_recall", round.getSessionId(), "撤回消息撤销下注");
        turnover.removePlayerTurnover(user.getId(), amount, round.getSessionId());
        out.put("cancelled", true);
        out.put("userId", user.getId());
        out.put("door", door);
        out.put("amount", amount);
        out.put("balance", user.getBalance());
        return out;
    }

    /** 撤回消息撤销该 IM 消息对应的全部下注。 */
    @Transactional
    public Map<String, Object> cancelBetsByImMessageId(long imMessageId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<SangongBet> betList = bets.listByImMessageId(imMessageId);
        if (betList.isEmpty()) {
            out.put("cancelled", false);
            out.put("reason", "bet_not_found");
            return out;
        }
        SangongBet first = betList.get(0);
        SangongRound round = roundRepo.findById(first.getRoundId()).orElse(null);
        if (round == null) {
            out.put("cancelled", false);
            out.put("reason", "round_not_found");
            return out;
        }
        if (!round.allowsBetting()) {
            out.put("cancelled", false);
            out.put("reason", "round_not_betting");
            return out;
        }
        SangongUser user = users.findById(first.getUserId());
        if (user == null) {
            out.put("cancelled", false);
            out.put("reason", "user_not_found");
            return out;
        }
        List<Integer> doors = new ArrayList<>();
        long totalAmount = 0;
        for (SangongBet bet : betList) {
            doors.add(bet.getDoor());
            totalAmount += bet.getAmount();
        }
        long sessionId = round.getSessionId();
        for (SangongBet bet : betList) {
            bets.delete(bet.getId());
            user = balance.releaseHold(user, bet.getAmount(), "bet_recall", sessionId, "撤回消息撤销下注");
        }
        turnover.removePlayerTurnover(user.getId(), totalAmount, sessionId);
        realtime.touch();
        out.put("cancelled", true);
        out.put("userId", user.getId());
        out.put("doors", doors);
        out.put("amount", first.getAmount());
        out.put("totalAmount", totalAmount);
        out.put("door", first.getDoor());
        out.put("balance", user.getBalance());
        return out;
    }

    public Map<Integer, Long> getDoorTotals(long roundId) {
        return bets.doorTotals(roundId);
    }

    /** 本局下注统计（截止后群发用）。 */
    public Map<String, Object> getRoundBetReport(SangongRound round) {
        int doorCount = gameSettings.getDoorCount();
        Integer bankerDoor = round.getBankerDoor();
        Map<Integer, Long> doorTotals = getDoorTotals(round.getId());

        String bankerNickname = null;
        String bankerImUserId = null;
        if (round.getBankerUserId() != null) {
            SangongUser banker = users.findById(round.getBankerUserId());
            if (banker != null) {
                bankerNickname = users.resolveNickname(banker);
                bankerImUserId = banker.getImUserId();
            }
        }

        Map<Long, Map<String, Object>> userRows = new LinkedHashMap<>();
        for (Map<String, Object> row : bets.userDoorRows(round.getId())) {
            long userId = ((Number) row.get("user_id")).longValue();
            Map<String, Object> u = userRows.computeIfAbsent(userId, id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", id);
                m.put("imUserId", String.valueOf(row.get("im_user_id")));
                m.put("nickname", String.valueOf(row.get("nickname")));
                m.put("doors", new LinkedHashMap<Integer, Long>());
                m.put("total", 0L);
                return m;
            });
            int door = ((Number) row.get("door")).intValue();
            long amount = ((Number) row.get("amount")).longValue();
            @SuppressWarnings("unchecked")
            Map<Integer, Long> doorsMap = (Map<Integer, Long>) u.get("doors");
            doorsMap.put(door, amount);
            u.put("total", ((Number) u.get("total")).longValue() + amount);
        }

        long grandTotal = 0;
        for (int door = 1; door <= doorCount; door++) {
            grandTotal += doorTotals.getOrDefault(door, 0L);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bankerDoor", bankerDoor);
        out.put("bankerNickname", bankerNickname);
        out.put("bankerImUserId", bankerImUserId);
        out.put("doorCount", doorCount);
        out.put("doorTotals", doorTotals);
        out.put("grandTotal", grandTotal);
        out.put("betCount", bets.countByRound(round.getId()));
        out.put("users", new ArrayList<>(userRows.values()));
        return out;
    }

    private void reject(SangongUser user, String reason) {
        im.notifyBetRejected(user, users.resolveNickname(user), reason);
    }

    private String phaseRejectReason(SangongRound round) {
        if (round.getDrawLockedAt() != null) {
            return "已录入开彩，不可下注";
        }
        return switch (round.getStatus()) {
            case SangongRound.AWAIT_BANKER -> "尚未定庄，当前不可下注";
            case SangongRound.AWAIT_BANKER_DOOR -> "尚未选定庄门，当前不可下注";
            case SangongRound.SETTLED -> "本局已结算，不可下注";
            default -> "当前不可下注";
        };
    }
}
