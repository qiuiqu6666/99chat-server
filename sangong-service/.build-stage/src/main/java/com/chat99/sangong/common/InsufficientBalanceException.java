package com.chat99.sangong.common;

public class InsufficientBalanceException extends BusinessException {
    private final long balance;
    public InsufficientBalanceException(long balance) {
        super("INSUFFICIENT_BALANCE", "余额不足", 422);
        this.balance = balance;
    }
    public long getBalance() { return balance; }
}
