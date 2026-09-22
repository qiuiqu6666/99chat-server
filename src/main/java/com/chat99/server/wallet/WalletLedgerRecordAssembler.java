package com.chat99.server.wallet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 将 WalletLedger 富集为 WalletLedgerRecord（批量关联充值/提现/转账/红包/闪兑单，避免 N+1）。 */
@Service
public class WalletLedgerRecordAssembler {

    private final WalletDepositRepository depositRepository;
    private final WalletWithdrawalRepository withdrawalRepository;
    private final WalletTransferRepository transferRepository;
    private final WalletRedPacketRepository redPacketRepository;
    private final WalletExchangeOrderRepository exchangeOrderRepository;
    private final WalletPartyNameResolver partyNameResolver;
    private final WalletGroupInfoResolver groupInfoResolver;

    public WalletLedgerRecordAssembler(WalletDepositRepository depositRepository,
                                       WalletWithdrawalRepository withdrawalRepository,
                                       WalletTransferRepository transferRepository,
                                       WalletRedPacketRepository redPacketRepository,
                                       WalletExchangeOrderRepository exchangeOrderRepository,
                                       WalletPartyNameResolver partyNameResolver,
                                       WalletGroupInfoResolver groupInfoResolver) {
        this.depositRepository = depositRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.transferRepository = transferRepository;
        this.redPacketRepository = redPacketRepository;
        this.exchangeOrderRepository = exchangeOrderRepository;
        this.partyNameResolver = partyNameResolver;
        this.groupInfoResolver = groupInfoResolver;
    }

    public List<WalletLedgerRecord> assemble(List<WalletLedger> ledgers) {
        if (ledgers == null || ledgers.isEmpty()) {
            return List.of();
        }
        Set<Long> depositIds = new HashSet<>();
        Set<Long> withdrawalIds = new HashSet<>();
        Set<Long> transferIds = new HashSet<>();
        Set<Long> redPacketIds = new HashSet<>();
        Set<Long> exchangeIds = new HashSet<>();
        for (WalletLedger l : ledgers) {
            if (isAdmin(l) || l.getRefId() == null) {
                continue;
            }
            String refType = l.getRefType();
            if (refType == null) {
                continue;
            }
            switch (refType) {
                case "DEPOSIT" -> depositIds.add(l.getRefId());
                case "WITHDRAW", "WITHDRAW_REFUND" -> withdrawalIds.add(l.getRefId());
                case "TRANSFER" -> transferIds.add(l.getRefId());
                case "RED_PACKET" -> redPacketIds.add(l.getRefId());
                case "EXCHANGE" -> exchangeIds.add(l.getRefId());
                default -> { /* no enrichment */ }
            }
        }
        Map<Long, WalletDeposit> deposits = byId(depositRepository.findAllById(depositIds), WalletDeposit::getId);
        Map<Long, WalletWithdrawal> withdrawals = byId(withdrawalRepository.findAllById(withdrawalIds), WalletWithdrawal::getId);
        Map<Long, WalletTransfer> transfers = byId(transferRepository.findAllById(transferIds), WalletTransfer::getId);
        Map<Long, WalletRedPacket> redPackets = byId(redPacketRepository.findAllById(redPacketIds), WalletRedPacket::getId);
        Map<Long, WalletExchangeOrder> exchanges = byId(exchangeOrderRepository.findAllById(exchangeIds), WalletExchangeOrder::getId);
        // 涉及方（转账双方/红包发送人/收款人）昵称+头像、群会话红包群名+群头像：均单次批量 DB 查询
        Map<String, WalletPartyNameResolver.PartyProfile> parties = resolvePartyProfiles(transfers, redPackets);
        Map<String, WalletGroupInfoResolver.GroupBrief> groups = resolveGroupBriefs(redPackets);

        List<WalletLedgerRecord> out = new ArrayList<>(ledgers.size());
        for (WalletLedger l : ledgers) {
            out.add(build(l, deposits, withdrawals, transfers, redPackets, exchanges, parties, groups));
        }
        return out;
    }

    /** 转账双方 + 红包发送人/定向收款人昵称头像（数据库 users 表，单次批量查询）。 */
    private Map<String, WalletPartyNameResolver.PartyProfile> resolvePartyProfiles(
            Map<Long, WalletTransfer> transfers, Map<Long, WalletRedPacket> redPackets) {
        Set<String> partyIds = new HashSet<>();
        for (WalletTransfer t : transfers.values()) {
            if (t == null) {
                continue;
            }
            addPartyId(partyIds, t.getFromUserId());
            addPartyId(partyIds, t.getToUserId());
        }
        for (WalletRedPacket rp : redPackets.values()) {
            if (rp == null) {
                continue;
            }
            addPartyId(partyIds, rp.getSenderUserId());
            addPartyId(partyIds, rp.getExclusiveUserId());
        }
        return partyNameResolver.resolveProfiles(partyIds);
    }

    /** 群会话红包（群转账/群红包）关联群名+群头像（数据库 group_profile 表，单次批量查询）。 */
    private Map<String, WalletGroupInfoResolver.GroupBrief> resolveGroupBriefs(
            Map<Long, WalletRedPacket> redPackets) {
        Set<String> groupIds = new HashSet<>();
        for (WalletRedPacket rp : redPackets.values()) {
            if (rp == null || !rp.isGroupConversation()) {
                continue;
            }
            addGroupId(groupIds, rp.getGroupId());
        }
        return groupInfoResolver.resolveGroups(groupIds);
    }

    private static void addPartyId(Set<String> partyIds, String userId) {
        if (userId != null && !userId.isBlank()) {
            partyIds.add(userId.trim());
        }
    }

    private static void addGroupId(Set<String> groupIds, String groupId) {
        if (groupId != null && !groupId.isBlank()) {
            groupIds.add(groupId.trim());
        }
    }

    private static <T> Map<Long, T> byId(List<T> list, Function<T, Long> idFn) {
        return list.stream().collect(Collectors.toMap(idFn, Function.identity(), (a, b) -> a));
    }

    private WalletLedgerRecord build(WalletLedger l,
                                     Map<Long, WalletDeposit> deposits,
                                     Map<Long, WalletWithdrawal> withdrawals,
                                     Map<Long, WalletTransfer> transfers,
                                     Map<Long, WalletRedPacket> redPackets,
                                     Map<Long, WalletExchangeOrder> exchanges,
                                     Map<String, WalletPartyNameResolver.PartyProfile> parties,
                                     Map<String, WalletGroupInfoResolver.GroupBrief> groups) {
        boolean admin = isAdmin(l);
        String refType = l.getRefType();
        long amount = l.getAmount();
        String direction = amount >= 0 ? "IN" : "OUT";
        String currency = mapCurrency(l.getCurrency());
        String source = resolveSource(admin, refType);
        String ledgerType = resolveLedgerType(l, admin, refType, currency);
        String network = ("CHAIN".equals(source) && l.getCurrency() == WalletCurrency.USDT) ? "TRC20" : "Platform";
        String refId = l.getRefId() == null ? "" : String.valueOf(l.getRefId());

        // defaults
        String status = "SUCCESS";
        String txId = null;
        String fromAddress = null;
        String toAddress = null;
        Long feeAmount = null;
        Integer confirmations = null;
        String failReason = null;
        String fromUserId = null;
        String toUserId = null;
        String memo = null;
        String clientOrderId = null;
        String packetId = null;
        String packetType = null;
        Integer packetCount = null;
        Integer remainingCount = null;
        Long totalAmount = null;
        String greeting = null;
        String senderUserId = null;
        String groupId = null;
        java.time.Instant expiresAt = null;
        Long inputAmount = null;
        Long outputAmount = null;
        String rateSnapshot = null;
        String displayTitle = null;
        String receiverUserId = null;
        String receiverName = null;
        String senderName = null;
        String toUserName = null;
        String senderAvatar = null;
        String receiverAvatar = null;
        String groupName = null;
        String groupAvatar = null;

        if (admin) {
            txId = "SYS-ADJ-" + l.getId();
            status = "SUCCESS";
            if (amount < 0 && l.getCurrency() == WalletCurrency.USDT) {
                toAddress = "OPERATIONS";
            }
        } else if ("DEPOSIT".equals(refType)) {
            WalletDeposit d = l.getRefId() == null ? null : deposits.get(l.getRefId());
            if (d != null) {
                txId = d.getTxId();
                fromAddress = d.getFromAddress();
                toAddress = d.getToAddress();
                confirmations = d.getConfirmations();
                status = d.getStatus() != null ? d.getStatus().name() : "CREDITED";
            } else {
                status = "CREDITED";
            }
        } else if ("WITHDRAW".equals(refType)) {
            WalletWithdrawal w = l.getRefId() == null ? null : withdrawals.get(l.getRefId());
            if (w != null) {
                txId = w.getTxId();
                toAddress = w.getToAddress();
                feeAmount = w.getFeeMicro();
                failReason = w.getFailReason();
                status = w.getStatus() != null ? w.getStatus().name() : "COMPLETED";
            } else {
                status = "COMPLETED";
            }
        } else if ("WITHDRAW_REFUND".equals(refType)) {
            status = "SUCCESS";
        } else if ("TRANSFER".equals(refType)) {
            WalletTransfer t = l.getRefId() == null ? null : transfers.get(l.getRefId());
            if (t != null) {
                fromUserId = t.getFromUserId();
                toUserId = t.getToUserId();
                memo = t.getMemo();
                clientOrderId = t.getClientOrderId();
                status = t.getStatus() != null ? t.getStatus().name() : "COMPLETED";
                // 统一字段：sender*/receiver* 昵称+头像（前端转账记录直接显示对方头像）
                senderUserId = fromUserId;
                receiverUserId = toUserId;
                WalletPartyNameResolver.PartyProfile fromProfile = profileOf(parties, fromUserId);
                if (fromProfile != null) {
                    senderName = fromProfile.nickname();
                    senderAvatar = fromProfile.avatarUrl();
                }
                WalletPartyNameResolver.PartyProfile toProfile = profileOf(parties, toUserId);
                if (toProfile != null) {
                    receiverName = toProfile.nickname();
                    receiverAvatar = toProfile.avatarUrl();
                    toUserName = receiverName;
                }
            } else {
                status = "COMPLETED";
            }
        } else if ("RED_PACKET".equals(refType)) {
            WalletRedPacket rp = l.getRefId() == null ? null : redPackets.get(l.getRefId());
            packetId = refId;
            if (rp != null) {
                packetType = rp.getPacketType() == null ? null : rp.getPacketType().apiCode();
                packetCount = rp.getPacketCount();
                remainingCount = rp.getRemainingCount();
                totalAmount = rp.getTotalAmount();
                greeting = rp.getGreeting();
                senderUserId = rp.getSenderUserId();
                // groupId 仅群会话返回（前端以 groupId 非空判断群聊）；C2C 恒为 null
                groupId = rp.cardGroupId();
                expiresAt = rp.getExpiresAt();
                // 发送人昵称+头像（收到红包时对方=发送人）
                WalletPartyNameResolver.PartyProfile senderProfile = profileOf(parties, senderUserId);
                if (senderProfile != null) {
                    senderName = senderProfile.nickname();
                    senderAvatar = senderProfile.avatarUrl();
                }
                // 收款人：定向红包/群转账发给 exclusiveUserId；群转账历史数据缺失时领取记录以本人兜底
                String receiver = rp.getExclusiveUserId();
                if (rp.getPacketType() == RedPacketType.GROUP_TRANSFER
                        && (receiver == null || receiver.isBlank())) {
                    receiver = l.getLedgerType() == WalletLedgerType.RED_PACKET_RECEIVE
                        ? l.getUserId() : null;
                }
                if (receiver != null && !receiver.isBlank()) {
                    receiverUserId = receiver;
                    toUserId = receiver;
                    WalletPartyNameResolver.PartyProfile receiverProfile = profileOf(parties, receiver);
                    if (receiverProfile != null) {
                        receiverName = receiverProfile.nickname();
                        receiverAvatar = receiverProfile.avatarUrl();
                        toUserName = receiverName;
                    }
                }
                if (rp.getPacketType() == RedPacketType.GROUP_TRANSFER) {
                    displayTitle = "群转账";
                }
                // 群会话（群转账/群红包）：补群名+群头像，前端优先显示群头像
                if (rp.isGroupConversation() && groupId != null && !groupId.isBlank()) {
                    WalletGroupInfoResolver.GroupBrief g = groups.get(groupId.trim());
                    if (g != null) {
                        groupName = g.groupName();
                        groupAvatar = g.groupAvatar();
                    }
                }
            }
            status = l.getLedgerType() == WalletLedgerType.RED_PACKET_SEND && rp != null && rp.getStatus() != null
                ? rp.getStatus().name()
                : "SUCCESS";
        } else if ("EXCHANGE".equals(refType)) {
            WalletExchangeOrder eo = l.getRefId() == null ? null : exchanges.get(l.getRefId());
            if (eo != null) {
                inputAmount = eo.getInputAmount();
                outputAmount = eo.getOutputAmount();
                rateSnapshot = eo.getRateSnapshot();
            }
            status = "SUCCESS";
        }

        return new WalletLedgerRecord(
            String.valueOf(l.getId()),
            ledgerType,
            source,
            refType,
            refId,
            status,
            currency,
            amount,
            direction,
            l.getCreatedAt(),
            network,
            txId,
            fromAddress,
            toAddress,
            feeAmount,
            confirmations,
            failReason,
            fromUserId,
            toUserId,
            l.getCounterpartUserId(),
            l.getRemark(),
            memo,
            clientOrderId,
            packetId,
            packetType,
            packetCount,
            remainingCount,
            totalAmount,
            greeting,
            senderUserId,
            groupId,
            expiresAt,
            inputAmount,
            outputAmount,
            rateSnapshot,
            displayTitle,
            receiverUserId,
            receiverName,
            senderName,
            toUserName,
            senderAvatar,
            receiverAvatar,
            groupName,
            groupAvatar);
    }

    private static WalletPartyNameResolver.PartyProfile profileOf(
            Map<String, WalletPartyNameResolver.PartyProfile> parties, String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return parties.get(userId.trim());
    }

    private static boolean isAdmin(WalletLedger l) {
        return l.getLedgerType() == WalletLedgerType.ADMIN_ADJUST
            || "ADMIN_ADJUST".equals(l.getRefType());
    }

    private static String resolveSource(boolean admin, String refType) {
        if (admin) {
            return "INTERNAL";
        }
        if ("DEPOSIT".equals(refType) || "WITHDRAW".equals(refType) || "WITHDRAW_REFUND".equals(refType)) {
            return "CHAIN";
        }
        return "PLATFORM";
    }

    /** 运营调账显示类型：USDT→DEPOSIT/WITHDRAW；平台币→EXCHANGE_IN/EXCHANGE_OUT。 */
    private static String resolveLedgerType(WalletLedger l, boolean admin, String refType, String currency) {
        if (admin) {
            if ("USDT".equals(currency)) {
                return l.getAmount() >= 0 ? "DEPOSIT" : "WITHDRAW";
            }
            return l.getAmount() >= 0 ? "EXCHANGE_IN" : "EXCHANGE_OUT";
        }
        if ("WITHDRAW_REFUND".equals(refType)) {
            return "WITHDRAW_REFUND";
        }
        return l.getLedgerType().name();
    }

    private static String mapCurrency(WalletCurrency currency) {
        if (currency == WalletCurrency.CNY || currency == WalletCurrency.PLATFORM) {
            return WalletCurrency.PLATFORM.getApiCode();
        }
        return currency.getApiCode();
    }
}
