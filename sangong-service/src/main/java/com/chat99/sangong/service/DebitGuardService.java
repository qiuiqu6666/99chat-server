package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.CoBankRepository;
import com.chat99.sangong.repository.RoundRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 下分护栏：庄/合庄未结算不可下分；余额不足不可下分。 */
@Service
public class DebitGuardService {
    private final BalanceService balance;
    private final RoundRepository rounds;
    private final CoBankRepository coBanks;

    public DebitGuardService(BalanceService balance, RoundRepository rounds, CoBankRepository coBanks) {
        this.balance = balance;
        this.rounds = rounds;
        this.coBanks = coBanks;
    }

    public Map<String, Object> validateDebit(SangongUser user, long amount) {
        SangongRound activeRound = findActiveBankerRound(user);
        Map<String, Object> out = new LinkedHashMap<>();
        if (activeRound != null) {
            out.put("ok", false);
            out.put("code", "DEBIT_BLOCKED_BANKER");
            out.put("message", "当前有未结算的庄/合庄对局，暂不可下分");
            out.put("balance", balance.getBalance(user));
            out.put("periodNo", activeRound.getPeriodNo());
            return out;
        }
        long bal = balance.getBalance(user);
        if (bal < amount) {
            out.put("ok", false);
            out.put("code", "INSUFFICIENT_BALANCE");
            out.put("message", "余额不足，无法下分");
            out.put("balance", bal);
            return out;
        }
        out.put("ok", true);
        out.put("balance", bal);
        return out;
    }

    private SangongRound findActiveBankerRound(SangongUser user) {
        SangongRound asBanker = rounds.findActiveByBanker(user.getId()).orElse(null);
        if (asBanker != null) {
            return asBanker;
        }
        List<Long> roundIds = coBanks.roundIdsByUser(user.getId());
        return rounds.findActiveByIds(roundIds).orElse(null);
    }
}
