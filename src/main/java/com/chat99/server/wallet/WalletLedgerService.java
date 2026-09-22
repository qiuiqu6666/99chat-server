package com.chat99.server.wallet;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletLedgerService {

    private final UserWalletRepository walletRepository;
    private final WalletLedgerRepository ledgerRepository;

    public WalletLedgerService(UserWalletRepository walletRepository, WalletLedgerRepository ledgerRepository) {
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional
    public UserWallet credit(String userId, WalletCurrency currency, long amount,
                             WalletLedgerType type, String refType, Long refId,
                             String counterpartUserId, String remark) {
        return apply(userId, currency, amount, type, refType, refId, counterpartUserId, remark);
    }

    @Transactional
    public UserWallet debit(String userId, WalletCurrency currency, long amount,
                            WalletLedgerType type, String refType, Long refId,
                            String counterpartUserId, String remark) {
        if (amount <= 0) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
        }
        return apply(userId, currency, -amount, type, refType, refId, counterpartUserId, remark);
    }

    private UserWallet apply(String userId, WalletCurrency currency, long signedAmount,
                             WalletLedgerType type, String refType, Long refId,
                             String counterpartUserId, String remark) {
        for (int i = 0; i < 3; i++) {
            try {
                return applyOnce(userId, currency, signedAmount, type, refType, refId, counterpartUserId, remark);
            } catch (OptimisticLockingFailureException e) {
                if (i == 2) throw e;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private UserWallet applyOnce(String userId, WalletCurrency currency, long signedAmount,
                                 WalletLedgerType type, String refType, Long refId,
                                 String counterpartUserId, String remark) {
        UserWallet w = walletRepository.findById(userId)
            .orElseThrow(() -> WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND"));
        long balance = readBalance(w, currency);
        long next = balance + signedAmount;
        if (next < 0) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE");
        }
        writeBalance(w, currency, next);
        walletRepository.save(w);

        WalletLedger entry = new WalletLedger();
        entry.setUserId(userId);
        entry.setCurrency(currency);
        entry.setAmount(signedAmount);
        entry.setBalanceAfter(next);
        entry.setLedgerType(type);
        entry.setRefType(refType);
        entry.setRefId(refId);
        entry.setCounterpartUserId(counterpartUserId);
        entry.setRemark(remark);
        ledgerRepository.save(entry);
        return w;
    }

    public static long readBalance(UserWallet w, WalletCurrency currency) {
        return switch (currency) {
            case USDT -> w.getBalanceUsdtMicro();
            case TRX -> w.getBalanceTrxSun();
            case CNY, PLATFORM -> w.getBalancePlatformFen();
        };
    }

    static void writeBalance(UserWallet w, WalletCurrency currency, long amount) {
        switch (currency) {
            case USDT -> w.setBalanceUsdtMicro(amount);
            case TRX -> w.setBalanceTrxSun(amount);
            case CNY, PLATFORM -> w.setBalancePlatformFen(amount);
        }
    }
}
