package com.chat99.server.wallet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * 钱包总记录页（WalletRecordScreen）聚合查询：客户端并行拉 deposits / withdrawals / ledger(闪兑等)，
 * 运营调账需落入这些接口而非仅全量 ledger。
 */
final class WalletRecordQuerySupport {

    /** App {@code getHistoryRecords()} 对 ledger 请求的 type 集合（含历史误传的 WITHDRAW_REFUND）。 */
    static final Set<WalletLedgerType> WALLET_RECORD_LEDGER_ANCHORS = Set.of(
        WalletLedgerType.EXCHANGE_OUT,
        WalletLedgerType.EXCHANGE_IN,
        WalletLedgerType.RED_PACKET_REFUND);

    private static final long SYNTHETIC_DEPOSIT_ID_BASE = 7_000_000_000L;
    private static final long SYNTHETIC_WITHDRAWAL_ID_BASE = 8_000_000_000L;

    private WalletRecordQuerySupport() {}

    static boolean isWalletRecordLedgerQuery(List<WalletLedgerType> requested) {
        if (requested == null || requested.isEmpty()) {
            return false;
        }
        for (WalletLedgerType t : requested) {
            if (WALLET_RECORD_LEDGER_ANCHORS.contains(t) || t == WalletLedgerType.WITHDRAW_REFUND) {
                return true;
            }
        }
        return false;
    }

    static List<WalletLedgerType> ledgerTypesForDbIn(List<WalletLedgerType> requested) {
        List<WalletLedgerType> types = new ArrayList<>();
        for (WalletLedgerType t : requested) {
            if (t == WalletLedgerType.WITHDRAW_REFUND) {
                types.add(WalletLedgerType.WITHDRAW);
            } else if (t != WalletLedgerType.ADMIN_ADJUST) {
                types.add(t);
            }
        }
        return types.stream().distinct().toList();
    }

    static boolean includeWithdrawRefund(List<WalletLedgerType> requested) {
        return requested != null && requested.contains(WalletLedgerType.WITHDRAW_REFUND);
    }

    static WalletLedgerView toWalletRecordLedgerView(WalletLedger e) {
        if (isAdminAdjust(e)) {
            String displayType = e.getAmount() >= 0
                ? WalletLedgerType.EXCHANGE_IN.name()
                : WalletLedgerType.EXCHANGE_OUT.name();
            return baseView(e, displayType, mapCurrency(e.getCurrency()));
        }
        if (e.getLedgerType() == WalletLedgerType.WITHDRAW
            && "WITHDRAW_REFUND".equals(e.getRefType())) {
            return baseView(e, WalletLedgerType.WITHDRAW_REFUND.name(), mapCurrency(e.getCurrency()));
        }
        return WalletLedgerView.from(e);
    }

    static boolean isAdminAdjust(WalletLedger e) {
        return e.getLedgerType() == WalletLedgerType.ADMIN_ADJUST
            || "ADMIN_ADJUST".equals(e.getRefType());
    }

    static Page<WalletDeposit> mergeDeposits(Page<WalletDeposit> chain,
                                             List<WalletLedger> adminCredits,
                                             Pageable pageable) {
        List<WalletDeposit> merged = new ArrayList<>(chain.getContent());
        for (WalletLedger l : adminCredits) {
            if (l.getCurrency() == WalletCurrency.USDT && l.getAmount() > 0) {
                merged.add(toSyntheticDeposit(l));
            }
        }
        merged.sort(Comparator.comparing(WalletDeposit::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed());
        return slicePage(merged, pageable, chain.getTotalElements() + adminCredits.size());
    }

    static Page<WalletWithdrawal> mergeWithdrawals(Page<WalletWithdrawal> chain,
                                                   List<WalletLedger> adminDebits,
                                                   Pageable pageable) {
        List<WalletWithdrawal> merged = new ArrayList<>(chain.getContent());
        for (WalletLedger l : adminDebits) {
            if (l.getCurrency() == WalletCurrency.USDT && l.getAmount() < 0) {
                merged.add(toSyntheticWithdrawal(l));
            }
        }
        merged.sort(Comparator.comparing(WalletWithdrawal::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed());
        return slicePage(merged, pageable, chain.getTotalElements() + adminDebits.size());
    }

    private static WalletDeposit toSyntheticDeposit(WalletLedger l) {
        WalletDeposit d = new WalletDeposit();
        d.setId(SYNTHETIC_DEPOSIT_ID_BASE + l.getId());
        d.setUserId(l.getUserId());
        d.setTxId("SYS-ADJ-" + l.getId());
        d.setLogIndex(0);
        d.setFromAddress("");
        d.setToAddress("");
        d.setAmountMicro(l.getAmount());
        d.setConfirmations(19);
        d.setStatus(DepositStatus.CREDITED);
        Instant at = l.getCreatedAt();
        d.setBlockTimestamp(at);
        d.setCreditedAt(at);
        d.setCreatedAt(at);
        return d;
    }

    private static WalletWithdrawal toSyntheticWithdrawal(WalletLedger l) {
        WalletWithdrawal w = new WalletWithdrawal();
        w.setId(SYNTHETIC_WITHDRAWAL_ID_BASE + l.getId());
        w.setUserId(l.getUserId());
        w.setToAddress("OPERATIONS");
        long micro = Math.abs(l.getAmount());
        w.setAmountMicro(micro);
        w.setFeeMicro(0);
        w.setPayoutMicro(micro);
        w.setStatus(WithdrawalStatus.COMPLETED);
        w.setTxId("SYS-ADJ-" + l.getId());
        w.setFailReason(l.getRemark());
        Instant at = l.getCreatedAt();
        w.setCreatedAt(at);
        w.setCompletedAt(at);
        return w;
    }

    private static <T> Page<T> slicePage(List<T> merged, Pageable pageable, long totalHint) {
        int page = pageable.getPageNumber();
        int size = pageable.getPageSize();
        int from = Math.min(page * size, merged.size());
        int to = Math.min(from + size, merged.size());
        List<T> slice = merged.subList(from, to);
        long total = Math.max(totalHint, merged.size());
        return new PageImpl<>(slice, pageable, total);
    }

    private static WalletLedgerView baseView(WalletLedger e, String displayType, String displayCurrency) {
        return new WalletLedgerView(
            e.getId(),
            e.getUserId(),
            displayCurrency,
            e.getAmount(),
            e.getBalanceAfter(),
            displayType,
            e.getRefType(),
            e.getRefId(),
            e.getCounterpartUserId(),
            e.getRemark(),
            e.getCreatedAt());
    }

    private static String mapCurrency(WalletCurrency currency) {
        if (currency == WalletCurrency.CNY || currency == WalletCurrency.PLATFORM) {
            return WalletCurrency.PLATFORM.getApiCode();
        }
        return currency.getApiCode();
    }
}
