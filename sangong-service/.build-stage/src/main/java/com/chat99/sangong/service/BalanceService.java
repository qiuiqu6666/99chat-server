package com.chat99.sangong.service;

import com.chat99.sangong.common.InsufficientBalanceException;
import com.chat99.sangong.domain.SangongLedger;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.repository.LedgerRepository;
import com.chat99.sangong.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 余额账务：加锁改余额并写流水；结算 debit 允许余额变负（与 PHP BalanceService 一致）。
 */
@Service
public class BalanceService {
    public record BalanceResult(SangongUser user, SangongLedger ledger) {}

    private final UserRepository users;
    private final LedgerRepository ledgers;

    public BalanceService(UserRepository users, LedgerRepository ledgers) {
        this.users = users;
        this.ledgers = ledgers;
    }

    public long getBalance(SangongUser user) {
        return user.getBalance();
    }

    public boolean hasEnough(SangongUser user, long amount) {
        return getBalance(user) >= amount;
    }

    public BalanceResult credit(SangongUser user, long amount, String type, Long sessionId,
                                String note, String refType, Long refId, String operator) {
        if (amount <= 0) {
            throw new RuntimeException("上分金额须为正整数");
        }
        return applyDelta(user, amount, type, sessionId, note, refType, refId, operator);
    }

    public BalanceResult debit(SangongUser user, long amount, String type, Long sessionId,
                               String note, String refType, Long refId, String operator) {
        if (amount <= 0) {
            throw new RuntimeException("下分金额须为正整数");
        }
        return applyDelta(user, -amount, type, sessionId, note, refType, refId, operator);
    }

    public SangongUser reserveForBet(SangongUser user, long amount, Long sessionId) {
        if (amount <= 0) {
            throw new RuntimeException("下注金额须为正整数");
        }
        if (!hasEnough(user, amount)) {
            throw new InsufficientBalanceException(getBalance(user));
        }
        return applyDelta(user, -amount, "bet_hold", sessionId, "下注扣款", null, null, null).user();
    }

    public SangongUser releaseHold(SangongUser user, long amount, String type, Long sessionId, String note) {
        if (amount <= 0) {
            throw new RuntimeException("退还金额须为正整数");
        }
        return applyDelta(user, amount, type, sessionId, note, null, null, null).user();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public BalanceResult applyDelta(SangongUser user, long delta, String type, Long sessionId,
                                    String note, String refType, Long refId, String operator) {
        SangongUser locked = users.lockById(user.getId());
        if (locked == null) {
            throw new RuntimeException("用户不存在");
        }
        long newBalance = locked.getBalance() + delta;
        users.updateBalance(locked.getId(), newBalance);
        locked.setBalance(newBalance);

        SangongLedger ledger = new SangongLedger();
        ledger.setUserId(locked.getId());
        ledger.setGroupId(locked.getGroupId());
        ledger.setSessionId(sessionId);
        ledger.setType(type);
        ledger.setAmount(delta);
        ledger.setBalanceAfter(newBalance);
        ledger.setRefType(refType);
        ledger.setRefId(refId);
        ledger.setNote(note == null ? "" : note);
        ledger.setOperator(operator == null ? "" : operator);
        ledgers.insert(ledger);

        return new BalanceResult(locked, ledger);
    }
}
