package com.chat99.server.adminapi;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.DepositStatus;
import com.chat99.server.wallet.ExchangeDirection;
import com.chat99.server.wallet.RedPacketStatus;
import com.chat99.server.wallet.RedPacketType;
import com.chat99.server.wallet.WalletCurrency;
import com.chat99.server.wallet.WalletDeposit;
import com.chat99.server.wallet.WalletDepositRepository;
import com.chat99.server.wallet.WalletExchangeOrder;
import com.chat99.server.wallet.WalletExchangeOrderRepository;
import com.chat99.server.wallet.WalletLedger;
import com.chat99.server.wallet.WalletLedgerRepository;
import com.chat99.server.wallet.WalletLedgerType;
import com.chat99.server.wallet.WalletRedPacket;
import com.chat99.server.wallet.WalletRedPacketRepository;
import com.chat99.server.wallet.WalletTransfer;
import com.chat99.server.wallet.WalletTransferRepository;
import com.chat99.server.wallet.WalletTransferStatus;
import com.chat99.server.wallet.WalletWithdrawal;
import com.chat99.server.wallet.WalletWithdrawalRepository;
import com.chat99.server.wallet.WithdrawalStatus;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class AdminFinanceService {

    private static final Map<String, String> TRANSACTION_TYPE_LABELS = Map.of(
        "1", "deposit",
        "2", "withdraw",
        "3", "transfer_out",
        "4", "transfer_in",
        "5", "red_packet_send",
        "6", "red_packet_receive",
        "7", "red_packet_refund");

    private final WalletLedgerRepository ledgerRepository;
    private final WalletDepositRepository depositRepository;
    private final WalletWithdrawalRepository withdrawalRepository;
    private final WalletTransferRepository transferRepository;
    private final WalletExchangeOrderRepository exchangeOrderRepository;
    private final WalletRedPacketRepository redPacketRepository;
    private final UserRepository userRepository;
    private final ImAdminClient imAdminClient;

    public AdminFinanceService(WalletLedgerRepository ledgerRepository,
                               WalletDepositRepository depositRepository,
                               WalletWithdrawalRepository withdrawalRepository,
                               WalletTransferRepository transferRepository,
                               WalletExchangeOrderRepository exchangeOrderRepository,
                               WalletRedPacketRepository redPacketRepository,
                               UserRepository userRepository,
                               ImAdminClient imAdminClient) {
        this.ledgerRepository = ledgerRepository;
        this.depositRepository = depositRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.transferRepository = transferRepository;
        this.exchangeOrderRepository = exchangeOrderRepository;
        this.redPacketRepository = redPacketRepository;
        this.userRepository = userRepository;
        this.imAdminClient = imAdminClient;
    }

    public FinanceListResponse listLedger(String userUid, String transactionTypes, String status,
                                          int page, int pageSize, String sort) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        List<Integer> typeFilter = parseTransactionTypes(transactionTypes);
        Specification<WalletLedger> spec = buildLedgerSpec(blankToNull(userUid), typeFilter);
        Page<WalletLedger> result = ledgerRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, resolveLedgerSort(sort)));
        Map<String, String> nicknames = loadNicknames(collectUserIdsFromLedgers(result.getContent()));
        List<FinanceTransactionItem> items = result.getContent().stream()
            .map(l -> toTransactionItem(l, nicknames))
            .filter(item -> matchesStatus(item.status(), status))
            .toList();
        return new FinanceListResponse(
            "wallet_ledger",
            items,
            result.getTotalElements(),
            safePage,
            safeSize,
            result.hasNext(),
            TRANSACTION_TYPE_LABELS,
            typeFilter.isEmpty() ? List.of(1, 2, 3, 4, 5, 6, 7) : typeFilter);
    }

    public FinanceListResponse listTransfers(String userUid, String transactionTypes, String status,
                                             String keyword, int page, int pageSize, String sort) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        List<Integer> types = parseTransactionTypes(transactionTypes);
        if (types.isEmpty()) {
            types = List.of(3, 4);
        }
        if (types.stream().anyMatch(t -> t == 1 || t == 2 || t == 5 || t == 6 || t == 7)) {
            return listLedger(userUid, transactionTypes, status, page, pageSize, sort);
        }
        String uid = blankToNull(userUid);
        boolean onlyIn = types.size() == 1 && types.contains(4);
        boolean onlyOut = types.size() == 1 && types.contains(3);
        Specification<WalletTransfer> spec = buildTransferSpec(uid, keyword, onlyOut, onlyIn);
        Page<WalletTransfer> result = transferRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, resolveTransferSort(sort)));
        Map<String, String> nicknames = loadNicknames(collectUserIdsFromTransfers(result.getContent()));
        List<FinanceTransactionItem> items = result.getContent().stream()
            .map(t -> toTransferItem(t, resolveTransferType(t, uid, onlyIn), nicknames))
            .filter(item -> matchesStatus(item.status(), status))
            .toList();
        return new FinanceListResponse(
            "wallet_transfer",
            items,
            result.getTotalElements(),
            safePage,
            safeSize,
            result.hasNext(),
            Map.of("3", "transfer_out", "4", "transfer_in"),
            types);
    }

    public FinanceListResponse listExchanges(String userUid, String direction, String keyword,
                                             int page, int pageSize, String sort) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Specification<WalletExchangeOrder> spec = buildExchangeSpec(
            blankToNull(userUid), blankToNull(direction), blankToNull(keyword));
        Page<WalletExchangeOrder> result = exchangeOrderRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, resolveExchangeSort(sort)));
        Map<String, String> nicknames = loadNicknames(collectUserIdsFromExchanges(result.getContent()));
        List<FinanceExchangeItem> items = result.getContent().stream()
            .map(o -> toExchangeItem(o, nicknames))
            .toList();
        return new FinanceListResponse(
            "wallet_exchange",
            items,
            result.getTotalElements(),
            safePage,
            safeSize,
            result.hasNext(),
            Map.of(
                "USDT_TO_PLATFORM", "usdt_to_platform",
                "PLATFORM_TO_USDT", "platform_to_usdt"),
            null);
    }

    public FinanceListResponse listRecharges(String userUid, String keyword, String status,
                                             int page, int pageSize, String sort) {
        return listRechargeWithdraw(userUid, keyword, status, true, false, page, pageSize, sort);
    }

    public FinanceListResponse listWithdrawals(String userUid, String keyword, String status,
                                               int page, int pageSize, String sort) {
        return listRechargeWithdraw(userUid, keyword, status, false, true, page, pageSize, sort);
    }

    public FinanceListResponse listRedPackets(String userUid, String keyword, String status,
                                             int page, int pageSize, String sort) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Specification<WalletRedPacket> spec = buildRedPacketSpec(blankToNull(userUid), keyword, status);
        Page<WalletRedPacket> result = redPacketRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, resolveRedPacketSort(sort)));
        List<WalletRedPacket> packets = result.getContent();
        Set<String> userIds = new HashSet<>();
        List<String> groupIds = new ArrayList<>();
        for (WalletRedPacket p : packets) {
            userIds.add(p.getSenderUserId());
            if (p.getExclusiveUserId() != null && !p.getExclusiveUserId().isBlank()) {
                userIds.add(p.getExclusiveUserId());
            }
            if (p.getGroupId() != null && !p.getGroupId().isBlank()) {
                groupIds.add(p.getGroupId().trim());
            }
        }
        Map<String, String> nicknames = loadNicknames(userIds);
        Map<String, String> groupNames = loadGroupNames(groupIds);
        List<FinanceRedPacketItem> items = packets.stream()
            .map(p -> toRedPacketItem(p, nicknames, groupNames))
            .toList();
        return new FinanceListResponse(
            "wallet_red_packet",
            items,
            result.getTotalElements(),
            safePage,
            safeSize,
            result.hasNext(),
            null,
            null);
    }

    private FinanceListResponse listRechargeWithdraw(String userUid, String keyword, String status,
                                                     boolean recharge, boolean withdraw,
                                                     int page, int pageSize, String sort) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        String uid = blankToNull(userUid);
        List<Object> merged = new ArrayList<>();
        if (recharge) {
            Page<WalletDeposit> deps = depositRepository.findAll(
                buildDepositSpec(uid, keyword),
                PageRequest.of(safePage - 1, safeSize, resolveDepositSort(sort)));
            merged.addAll(deps.getContent());
        }
        if (withdraw) {
            Page<WalletWithdrawal> wds = withdrawalRepository.findAll(
                buildWithdrawalSpec(uid, keyword, status),
                PageRequest.of(safePage - 1, safeSize, resolveWithdrawalSort(sort)));
            merged.addAll(wds.getContent());
        }
        merged.sort((a, b) -> Long.compare(extractTime(b), extractTime(a)));
        Set<String> userIds = new HashSet<>();
        for (Object row : merged) {
            if (row instanceof WalletDeposit d) {
                userIds.add(d.getUserId());
            } else if (row instanceof WalletWithdrawal w) {
                userIds.add(w.getUserId());
            }
        }
        Map<String, String> nicknames = loadNicknames(userIds);
        List<FinanceRechargeWithdrawItem> items = new ArrayList<>();
        for (Object row : merged) {
            if (row instanceof WalletDeposit d) {
                FinanceRechargeWithdrawItem item = toRechargeItem(d, nicknames);
                if (matchesRechargeWithdrawStatus(item.status(), status)) {
                    items.add(item);
                }
            } else if (row instanceof WalletWithdrawal w) {
                FinanceRechargeWithdrawItem item = toWithdrawItem(w, nicknames);
                if (matchesRechargeWithdrawStatus(item.status(), status)) {
                    items.add(item);
                }
            }
        }
        long total = recharge && !withdraw
            ? depositRepository.count(buildDepositSpec(uid, keyword))
            : withdraw && !recharge
                ? withdrawalRepository.count(buildWithdrawalSpec(uid, keyword, status))
                : items.size();
        return new FinanceListResponse(
            recharge && withdraw ? "wallet_recharge_withdraw" : (recharge ? "wallet_deposit" : "wallet_withdrawal"),
            items,
            total,
            safePage,
            safeSize,
            (long) safePage * safeSize < total,
            null,
            null);
    }

    private FinanceTransactionItem toTransactionItem(WalletLedger l, Map<String, String> nicknames) {
        int type = resolveTransactionType(l);
        String owner = l.getUserId();
        String counter = l.getCounterpartUserId();
        String fromUid = "—";
        String toUid = "—";
        switch (l.getLedgerType()) {
            case TRANSFER_OUT -> {
                fromUid = owner;
                toUid = counter != null ? counter : "—";
            }
            case TRANSFER_IN -> {
                fromUid = counter != null ? counter : "—";
                toUid = owner;
            }
            case RED_PACKET_SEND -> {
                fromUid = owner;
                toUid = counter != null ? counter : "—";
            }
            case RED_PACKET_RECEIVE -> {
                fromUid = counter != null ? counter : "—";
                toUid = owner;
            }
            case DEPOSIT, ADMIN_ADJUST -> {
                if (l.getAmount() >= 0) {
                    toUid = owner;
                } else {
                    fromUid = owner;
                }
            }
            case WITHDRAW -> {
                fromUid = owner;
            }
            default -> {
                if (l.getAmount() >= 0) {
                    toUid = owner;
                } else {
                    fromUid = owner;
                }
            }
        }
        long absAmount = Math.abs(l.getAmount());
        String amountStr = formatAmount(l.getCurrency(), absAmount);
        String balanceAfter = formatAmount(l.getCurrency(), l.getBalanceAfter());
        String balanceBefore = formatAmount(l.getCurrency(), l.getBalanceAfter() - l.getAmount());
        return new FinanceTransactionItem(
            String.valueOf(l.getId()),
            type,
            fromUid,
            nicknames.getOrDefault(fromUid, "—"),
            toUid,
            nicknames.getOrDefault(toUid, "—"),
            amountStr,
            "0",
            balanceBefore,
            balanceAfter,
            mapCurrency(l.getCurrency()),
            "1",
            l.getRemark(),
            epochMs(l.getCreatedAt()));
    }

    private FinanceTransactionItem toTransferItem(WalletTransfer t, int type, Map<String, String> nicknames) {
        String from = t.getFromUserId();
        String to = t.getToUserId();
        return new FinanceTransactionItem(
            String.valueOf(t.getId()),
            type,
            from,
            nicknames.getOrDefault(from, "—"),
            to,
            nicknames.getOrDefault(to, "—"),
            formatAmount(t.getCurrency(), t.getAmount()),
            formatAmount(t.getCurrency(), t.getFeeAmount()),
            null,
            null,
            mapCurrency(t.getCurrency()),
            mapTransferStatus(t.getStatus()),
            t.getMemo(),
            epochMs(t.getCreatedAt()));
    }

    private FinanceExchangeItem toExchangeItem(WalletExchangeOrder o, Map<String, String> nicknames) {
        ExchangeDirection dir = o.getDirection();
        String inputCurrency;
        String outputCurrency;
        String inputAmount;
        String outputAmount;
        if (dir == ExchangeDirection.USDT_TO_PLATFORM) {
            inputCurrency = mapCurrency(WalletCurrency.USDT);
            outputCurrency = mapCurrency(WalletCurrency.PLATFORM);
            inputAmount = formatAmount(WalletCurrency.USDT, o.getInputAmount());
            outputAmount = formatAmount(WalletCurrency.PLATFORM, o.getOutputAmount());
        } else {
            inputCurrency = mapCurrency(WalletCurrency.PLATFORM);
            outputCurrency = mapCurrency(WalletCurrency.USDT);
            inputAmount = formatAmount(WalletCurrency.PLATFORM, o.getInputAmount());
            outputAmount = formatAmount(WalletCurrency.USDT, o.getOutputAmount());
        }
        return new FinanceExchangeItem(
            "EX" + o.getId(),
            o.getUserId(),
            nicknames.getOrDefault(o.getUserId(), "—"),
            mapExchangeDirection(dir),
            dir.name(),
            inputAmount,
            inputCurrency,
            outputAmount,
            outputCurrency,
            formatExchangeRate(o.getRateSnapshot()),
            "成功",
            epochMs(o.getCreatedAt()));
    }

    private FinanceRechargeWithdrawItem toRechargeItem(WalletDeposit d, Map<String, String> nicknames) {
        return new FinanceRechargeWithdrawItem(
            String.valueOf(d.getId()),
            d.getUserId(),
            nicknames.getOrDefault(d.getUserId(), "—"),
            "充值",
            "TRON/USDT",
            Double.parseDouble(AdminUserFormats.decimalFromMicro(d.getAmountMicro())),
            mapDepositStatus(d.getStatus()),
            d.getTxId(),
            d.getToAddress(),
            epochMs(d.getCreatedAt()));
    }

    private FinanceRechargeWithdrawItem toWithdrawItem(WalletWithdrawal w, Map<String, String> nicknames) {
        return new FinanceRechargeWithdrawItem(
            String.valueOf(w.getId()),
            w.getUserId(),
            nicknames.getOrDefault(w.getUserId(), "—"),
            "提现",
            "TRON/USDT",
            Double.parseDouble(AdminUserFormats.decimalFromMicro(w.getAmountMicro())),
            mapWithdrawalStatus(w),
            w.getTxId() != null ? w.getTxId() : "",
            w.getToAddress(),
            epochMs(w.getCreatedAt()));
    }

    private FinanceRedPacketItem toRedPacketItem(
            WalletRedPacket p, Map<String, String> nicknames, Map<String, String> groupNames) {
        int grabbed = Math.max(0, p.getPacketCount() - p.getRemainingCount());
        return new FinanceRedPacketItem(
            String.valueOf(p.getId()),
            p.getSenderUserId(),
            nicknames.getOrDefault(p.getSenderUserId(), "—"),
            mapPacketType(p.getPacketType()),
            resolveRedPacketTarget(p, nicknames, groupNames),
            formatAmount(p.getCurrency(), p.getTotalAmount()),
            grabbed,
            p.getPacketCount(),
            mapRedPacketStatus(p.getStatus()),
            epochMs(p.getCreatedAt()));
    }

    private static String resolveRedPacketTarget(
            WalletRedPacket p, Map<String, String> nicknames, Map<String, String> groupNames) {
        if (p.getExclusiveUserId() != null && !p.getExclusiveUserId().isBlank()) {
            String uid = p.getExclusiveUserId().trim();
            String nick = nicknames.get(uid);
            if (nick != null && !nick.isBlank() && !"—".equals(nick)) {
                return nick;
            }
            return uid;
        }
        if (p.getGroupId() != null && !p.getGroupId().isBlank()) {
            String gid = p.getGroupId().trim();
            String name = groupNames.get(gid);
            if (name != null && !name.isBlank()) {
                return "群 " + name;
            }
            return "群 " + gid;
        }
        return "—";
    }

    private Map<String, String> loadGroupNames(Collection<String> groupIds) {
        List<String> ids = groupIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (var entry : imAdminClient.fetchGroupAdminInfoMap(ids).entrySet()) {
            String name = entry.getValue().name();
            if (name != null && !name.isBlank()) {
                out.put(entry.getKey(), name);
            }
        }
        return out;
    }

    private Specification<WalletLedger> buildLedgerSpec(String userUid, List<Integer> typeFilter) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.notEqual(root.get("ledgerType"), WalletLedgerType.FEE));
            if (userUid != null) {
                preds.add(cb.equal(root.get("userId"), userUid));
            }
            if (typeFilter != null && !typeFilter.isEmpty()) {
                List<Predicate> typePreds = new ArrayList<>();
                for (int t : typeFilter) {
                    typePreds.add(transactionTypePredicate(root, cb, t));
                }
                preds.add(cb.or(typePreds.toArray(Predicate[]::new)));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private static Predicate transactionTypePredicate(
        jakarta.persistence.criteria.Root<WalletLedger> root,
        jakarta.persistence.criteria.CriteriaBuilder cb,
        int type) {
        return switch (type) {
            case 1 -> cb.or(
                cb.equal(root.get("ledgerType"), WalletLedgerType.DEPOSIT),
                cb.and(
                    cb.or(
                        cb.equal(root.get("ledgerType"), WalletLedgerType.ADMIN_ADJUST),
                        cb.equal(root.get("refType"), "ADMIN_ADJUST")),
                    cb.greaterThan(root.get("amount"), 0L)));
            case 2 -> cb.or(
                cb.equal(root.get("ledgerType"), WalletLedgerType.WITHDRAW),
                cb.and(
                    cb.or(
                        cb.equal(root.get("ledgerType"), WalletLedgerType.ADMIN_ADJUST),
                        cb.equal(root.get("refType"), "ADMIN_ADJUST")),
                    cb.lessThan(root.get("amount"), 0L)));
            case 3 -> cb.equal(root.get("ledgerType"), WalletLedgerType.TRANSFER_OUT);
            case 4 -> cb.equal(root.get("ledgerType"), WalletLedgerType.TRANSFER_IN);
            case 5 -> cb.equal(root.get("ledgerType"), WalletLedgerType.RED_PACKET_SEND);
            case 6 -> cb.equal(root.get("ledgerType"), WalletLedgerType.RED_PACKET_RECEIVE);
            case 7 -> cb.or(
                cb.equal(root.get("ledgerType"), WalletLedgerType.RED_PACKET_REFUND),
                cb.equal(root.get("ledgerType"), WalletLedgerType.EXCHANGE_IN),
                cb.equal(root.get("ledgerType"), WalletLedgerType.EXCHANGE_OUT),
                cb.and(
                    cb.equal(root.get("ledgerType"), WalletLedgerType.WITHDRAW),
                    cb.equal(root.get("refType"), "WITHDRAW_REFUND")));
            default -> cb.disjunction();
        };
    }

    private Specification<WalletTransfer> buildTransferSpec(
            String userUid, String keyword, boolean onlyOut, boolean onlyIn) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (userUid != null) {
                if (onlyOut) {
                    preds.add(cb.equal(root.get("fromUserId"), userUid));
                } else if (onlyIn) {
                    preds.add(cb.equal(root.get("toUserId"), userUid));
                } else {
                    preds.add(cb.or(
                        cb.equal(root.get("fromUserId"), userUid),
                        cb.equal(root.get("toUserId"), userUid)));
                }
            }
            if (keyword != null) {
                String like = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(root.get("memo")), like),
                    cb.like(cb.lower(root.get("clientOrderId")), like)));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private Specification<WalletExchangeOrder> buildExchangeSpec(
            String userUid, String direction, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (userUid != null) {
                preds.add(cb.equal(root.get("userId"), userUid));
            }
            ExchangeDirection dir = parseExchangeDirection(direction);
            if (dir != null) {
                preds.add(cb.equal(root.get("direction"), dir));
            }
            if (keyword != null) {
                String kw = keyword.trim();
                String lower = kw.toLowerCase(Locale.ROOT);
                List<Predicate> kwPreds = new ArrayList<>();
                kwPreds.add(cb.like(cb.lower(root.get("userId")), "%" + lower + "%"));
                if (kw.startsWith("EX") || kw.startsWith("ex")) {
                    try {
                        long id = Long.parseLong(kw.substring(2));
                        kwPreds.add(cb.equal(root.get("id"), id));
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                } else {
                    try {
                        long id = Long.parseLong(kw);
                        kwPreds.add(cb.equal(root.get("id"), id));
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
                preds.add(cb.or(kwPreds.toArray(Predicate[]::new)));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private static int resolveTransferType(WalletTransfer transfer, String userUid, boolean onlyIn) {
        if (onlyIn) {
            return 4;
        }
        if (userUid != null && userUid.equals(transfer.getToUserId())
            && !userUid.equals(transfer.getFromUserId())) {
            return 4;
        }
        return 3;
    }

    private Specification<WalletDeposit> buildDepositSpec(String userUid, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (userUid != null) {
                preds.add(cb.equal(root.get("userId"), userUid));
            }
            if (keyword != null) {
                String like = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(root.get("txId")), like),
                    cb.like(cb.lower(root.get("toAddress")), like)));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private Specification<WalletWithdrawal> buildWithdrawalSpec(String userUid, String keyword, String status) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (userUid != null) {
                preds.add(cb.equal(root.get("userId"), userUid));
            }
            if (keyword != null) {
                String like = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(root.get("txId")), like),
                    cb.like(cb.lower(root.get("toAddress")), like)));
            }
            WithdrawalStatus st = parseWithdrawalStatusFilter(status);
            if (st != null) {
                preds.add(cb.equal(root.get("status"), st));
            } else if (status != null && !status.isBlank()) {
                applyWithdrawalStatusLabelFilter(preds, cb, root, status.trim());
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private static void applyWithdrawalStatusLabelFilter(
            List<Predicate> preds,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Root<WalletWithdrawal> root,
            String status) {
        switch (status) {
            case "已通过", "成功" -> preds.add(cb.equal(root.get("status"), WithdrawalStatus.COMPLETED));
            case "处理中" -> preds.add(root.get("status").in(
                WithdrawalStatus.BROADCASTING, WithdrawalStatus.CONFIRMING));
            case "已拒绝" -> preds.add(cb.and(
                cb.equal(root.get("status"), WithdrawalStatus.FAILED),
                cb.like(root.get("failReason"), "%拒绝%")));
            case "失败" -> preds.add(cb.and(
                cb.equal(root.get("status"), WithdrawalStatus.FAILED),
                cb.or(
                    cb.isNull(root.get("failReason")),
                    cb.notLike(root.get("failReason"), "%拒绝%"))));
            default -> { }
        }
    }

    private static WithdrawalStatus parseWithdrawalStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim()) {
            case "待审核" -> WithdrawalStatus.PENDING;
            case "处理中" -> null;
            case "成功", "已通过", "已拒绝", "失败" -> null;
            default -> null;
        };
    }

    private Specification<WalletRedPacket> buildRedPacketSpec(String userUid, String keyword, String status) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (userUid != null) {
                preds.add(cb.equal(root.get("senderUserId"), userUid));
            }
            if (keyword != null) {
                String like = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(root.get("groupId")), like),
                    cb.like(cb.lower(root.get("greeting")), like)));
            }
            if (status != null && !status.isBlank()) {
                RedPacketStatus st = parseRedPacketStatusFilter(status);
                if (st != null) {
                    preds.add(cb.equal(root.get("status"), st));
                }
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private static int resolveTransactionType(WalletLedger l) {
        if (l.getLedgerType() == WalletLedgerType.ADMIN_ADJUST
            || "ADMIN_ADJUST".equals(l.getRefType())) {
            return l.getAmount() >= 0 ? 1 : 2;
        }
        if (l.getLedgerType() == WalletLedgerType.WITHDRAW
            && "WITHDRAW_REFUND".equals(l.getRefType())) {
            return 7;
        }
        return switch (l.getLedgerType()) {
            case DEPOSIT -> 1;
            case WITHDRAW -> 2;
            case TRANSFER_OUT -> 3;
            case TRANSFER_IN -> 4;
            case RED_PACKET_SEND -> 5;
            case RED_PACKET_RECEIVE -> 6;
            case RED_PACKET_REFUND, EXCHANGE_IN, EXCHANGE_OUT, EXCHANGE_SURPLUS -> 7;
            default -> 7;
        };
    }

    private static List<Integer> parseTransactionTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                int v = Integer.parseInt(part.trim());
                if (v >= 1 && v <= 7) {
                    out.add(v);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    private static boolean matchesStatus(String itemStatus, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return filter.trim().equals(itemStatus)
            || statusLabel(filter).equals(itemStatus);
    }

    private static boolean matchesRechargeWithdrawStatus(String itemStatus, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (filter.equals(itemStatus)) {
            return true;
        }
        // 列表展示用「已通过」，筛选项里「成功」与「已通过」同义；避免 count 有值但 items 被滤空
        boolean filterCompleted = "成功".equals(filter) || "已通过".equals(filter);
        boolean itemCompleted = "成功".equals(itemStatus) || "已通过".equals(itemStatus);
        return filterCompleted && itemCompleted;
    }

    private static String statusLabel(String filter) {
        return switch (filter.trim()) {
            case "0" -> "待审核";
            case "1" -> "成功";
            case "2" -> "失败";
            case "3" -> "已取消";
            default -> filter;
        };
    }

    private Map<String, String> loadNicknames(Collection<String> userIds) {
        Set<String> ids = new HashSet<>();
        for (String id : userIds) {
            if (id != null && !id.isBlank() && !"—".equals(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (User u : userRepository.findByUserIdIn(ids)) {
            out.put(u.getUserId(), u.getNickname());
        }
        return out;
    }

    private static Set<String> collectUserIdsFromLedgers(List<WalletLedger> rows) {
        Set<String> ids = new HashSet<>();
        for (WalletLedger l : rows) {
            ids.add(l.getUserId());
            if (l.getCounterpartUserId() != null) {
                ids.add(l.getCounterpartUserId());
            }
        }
        return ids;
    }

    private static Set<String> collectUserIdsFromTransfers(List<WalletTransfer> rows) {
        Set<String> ids = new HashSet<>();
        for (WalletTransfer t : rows) {
            ids.add(t.getFromUserId());
            ids.add(t.getToUserId());
        }
        return ids;
    }

    private static Set<String> collectUserIdsFromExchanges(List<WalletExchangeOrder> rows) {
        Set<String> ids = new HashSet<>();
        for (WalletExchangeOrder o : rows) {
            ids.add(o.getUserId());
        }
        return ids;
    }

    private static String formatAmount(WalletCurrency currency, long units) {
        if (currency == WalletCurrency.PLATFORM) {
            return AdminUserFormats.decimalFromFen(units);
        }
        return AdminUserFormats.decimalFromMicro(units);
    }

    private static String mapCurrency(WalletCurrency currency) {
        return currency == WalletCurrency.PLATFORM
            ? WalletCurrency.PLATFORM.getApiCode()
            : WalletCurrency.USDT.getApiCode();
    }

    private static String mapDepositStatus(DepositStatus status) {
        return switch (status) {
            case CREDITED -> "成功";
            case FAILED -> "失败";
            case DETECTED, CONFIRMING -> "处理中";
        };
    }

    private static String mapWithdrawalStatus(WalletWithdrawal w) {
        if (w == null || w.getStatus() == null) {
            return "—";
        }
        return switch (w.getStatus()) {
            case COMPLETED -> "已通过";
            case BROADCASTING, CONFIRMING -> "处理中";
            case PENDING -> "待审核";
            case FAILED -> isAdminRejectedWithdraw(w.getFailReason()) ? "已拒绝" : "失败";
        };
    }

    private static boolean isAdminRejectedWithdraw(String failReason) {
        if (failReason == null || failReason.isBlank()) {
            return false;
        }
        return failReason.contains("拒绝");
    }

    private static String mapWithdrawalStatus(WithdrawalStatus status) {
        return switch (status) {
            case COMPLETED -> "已通过";
            case BROADCASTING, CONFIRMING -> "处理中";
            case FAILED -> "失败";
            case PENDING -> "待审核";
        };
    }

    private static String mapTransferStatus(WalletTransferStatus status) {
        return status == WalletTransferStatus.COMPLETED ? "1" : "2";
    }

    private static String mapRedPacketStatus(RedPacketStatus status) {
        return switch (status) {
            case PENDING, PROCESSING, ACTIVE -> "0";
            case COMPLETED -> "1";
            case EXPIRED, REFUNDED -> "2";
        };
    }

    private static RedPacketStatus parseRedPacketStatusFilter(String status) {
        return switch (status.trim()) {
            case "0", "进行中" -> RedPacketStatus.ACTIVE;
            case "1", "已领完" -> RedPacketStatus.COMPLETED;
            case "2", "已过期" -> RedPacketStatus.EXPIRED;
            default -> null;
        };
    }

    private static String mapPacketType(RedPacketType type) {
        return switch (type) {
            case NORMAL_GROUP -> "普通群红包";
            case LUCKY_GROUP -> "拼手气群红包";
            case EXCLUSIVE -> "专属红包";
            case NORMAL_C2C -> "单聊红包";
            case GROUP_TRANSFER -> "群转账";
        };
    }

    private static String mapExchangeDirection(ExchangeDirection direction) {
        if (direction == ExchangeDirection.USDT_TO_PLATFORM) {
            return "USDT → CNY";
        }
        return "CNY → USDT";
    }

    private static ExchangeDirection parseExchangeDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "USDT_TO_PLATFORM", "USDT_TO_CNY", "USDT→CNY", "USDT->CNY" -> ExchangeDirection.USDT_TO_PLATFORM;
            case "PLATFORM_TO_USDT", "CNY_TO_USDT", "CNY→USDT", "CNY->USDT" -> ExchangeDirection.PLATFORM_TO_USDT;
            default -> {
                try {
                    yield ExchangeDirection.valueOf(v);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }

    private static String formatExchangeRate(String rateSnapshot) {
        if (rateSnapshot == null || rateSnapshot.isBlank()) {
            return "—";
        }
        String trimmed = rateSnapshot.trim();
        if (trimmed.startsWith("{")) {
            int idx = trimmed.indexOf("\"usdCny\"");
            if (idx >= 0) {
                int colon = trimmed.indexOf(':', idx);
                if (colon > 0) {
                    int end = trimmed.indexOf(',', colon);
                    if (end < 0) {
                        end = trimmed.indexOf('}', colon);
                    }
                    if (end > colon) {
                        String num = trimmed.substring(colon + 1, end).trim();
                        if (!num.isBlank()) {
                            return num;
                        }
                    }
                }
            }
        }
        return trimmed.length() > 24 ? trimmed.substring(0, 24) + "…" : trimmed;
    }

    private static long extractTime(Object row) {
        if (row instanceof WalletDeposit d) {
            return d.getCreatedAt() == null ? 0L : d.getCreatedAt().toEpochMilli();
        }
        if (row instanceof WalletWithdrawal w) {
            return w.getCreatedAt() == null ? 0L : w.getCreatedAt().toEpochMilli();
        }
        return 0L;
    }

    private static Long epochMs(java.time.Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Sort resolveLedgerSort(String sort) {
        if ("create_time_asc".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Direction.ASC, "createdAt");
        }
        return Sort.by(Sort.Direction.DESC, "createdAt");
    }

    private static Sort resolveTransferSort(String sort) {
        return resolveLedgerSort(sort);
    }

    private static Sort resolveExchangeSort(String sort) {
        return resolveLedgerSort(sort);
    }

    private static Sort resolveDepositSort(String sort) {
        return resolveLedgerSort(sort);
    }

    private static Sort resolveWithdrawalSort(String sort) {
        return resolveLedgerSort(sort);
    }

    private static Sort resolveRedPacketSort(String sort) {
        return resolveLedgerSort(sort);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static FinanceListResponse emptyFinanceList(int page, int pageSize) {
        return new FinanceListResponse("empty", List.of(), 0, page, pageSize, false, null, null);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FinanceTransactionItem(
        String transactionNo,
        int transactionType,
        String fromUid,
        String fromNickname,
        String toUid,
        String toNickname,
        String amount,
        String fee,
        String balanceBefore,
        String balanceAfter,
        String currency,
        String status,
        String remark,
        Long createTime) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FinanceRechargeWithdrawItem(
        String bizNo,
        String userUid,
        String nickname,
        String bizType,
        String channel,
        double amount,
        String status,
        String externalOrderNo,
        String address,
        Long createTime) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FinanceExchangeItem(
        String orderNo,
        String userUid,
        String nickname,
        String directionLabel,
        String direction,
        String inputAmount,
        String inputCurrency,
        String outputAmount,
        String outputCurrency,
        String rate,
        String status,
        Long createTime) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FinanceRedPacketItem(
        String id,
        String senderUid,
        String nickname,
        String packetType,
        String targetSummary,
        String totalAmount,
        int receiveCount,
        int totalCount,
        String status,
        Long createTime) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FinanceListResponse(
        String source,
        List<?> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore,
        Map<String, String> transactionTypes,
        List<Integer> transactionTypeFilter) {}
}
