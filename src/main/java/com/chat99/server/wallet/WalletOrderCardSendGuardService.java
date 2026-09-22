package com.chat99.server.wallet;

import com.chat99.server.im.ImUserIdService;
import com.chat99.server.wallet.WalletOrderCardImSupport.WalletCard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * IM BeforeSend：拦截伪造 / 重放的钱包自定义卡片（代码直接 sendMessage 同样生效）。
 */
@Service
public class WalletOrderCardSendGuardService {

    private static final Logger log = LoggerFactory.getLogger(WalletOrderCardSendGuardService.class);
    private static final String DEDUPE_KEY_PREFIX = "im:wallet-card:";

    private final WalletOrderCardGuardProperties props;
    private final WalletRedPacketRepository redPacketRepository;
    private final WalletTransferRepository transferRepository;
    private final ImUserIdService imUserIdService;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public WalletOrderCardSendGuardService(WalletOrderCardGuardProperties props,
                                           WalletRedPacketRepository redPacketRepository,
                                           WalletTransferRepository transferRepository,
                                           ImUserIdService imUserIdService,
                                           StringRedisTemplate redis,
                                           ObjectMapper json) {
        this.props = props;
        this.redPacketRepository = redPacketRepository;
        this.transferRepository = transferRepository;
        this.imUserIdService = imUserIdService;
        this.redis = redis;
        this.json = json;
    }

    public boolean isEnabled() {
        return props.enabled();
    }

    /**
     * @return 拒绝码；empty 表示放行或不适用
     */
    public Optional<String> evaluate(Map<String, Object> body) {
        if (!props.enabled()) {
            return Optional.empty();
        }
        List<WalletCard> cards = WalletOrderCardImSupport.extractCards(body, json);
        if (cards.isEmpty()) {
            return Optional.empty();
        }

        String fromIm = str(body.get("From_Account"));
        if (fromIm != null && isAllowlistedSender(fromIm)) {
            log.info("wallet-card beforeSend allowlisted from={} cards={}", fromIm, cards.size());
            return Optional.empty();
        }

        String groupId = str(body.get("GroupId"));
        String toIm = str(body.get("To_Account"));
        String fromUserId = resolveBusinessUserId(fromIm);

        Optional<String> reject = Optional.empty();
        for (WalletCard card : cards) {
            reject = validateOne(card, fromUserId, groupId, toIm);
            if (reject.isPresent()) {
                break;
            }
        }

        if (props.logOnly()) {
            log.info("wallet-card beforeSend from={} groupId={} to={} cards={} reject={} enforce={}",
                fromIm, groupId, toIm, cards.size(), reject.orElse("-"), props.enforce());
            if (!props.enforce()) {
                return Optional.empty();
            }
        } else if (reject.isPresent()) {
            log.warn("wallet-card beforeSend rejected from={} groupId={} to={} code={}",
                fromIm, groupId, toIm, reject.get());
        }
        return reject;
    }

    private Optional<String> validateOne(WalletCard card, String fromUserId,
                                         String groupId, String toIm) {
        if (fromUserId == null || fromUserId.isBlank()) {
            return Optional.of("WALLET_CARD_SENDER_MISMATCH");
        }
        String type = card.customType();
        if (WalletOrderCardImSupport.TYPE_TRANSFER.equals(type)) {
            return validateTransfer(card, fromUserId, groupId, toIm);
        }
        // red_packet / group_transfer / 仅 businessID
        return validateRedPacket(card, fromUserId, groupId, type);
    }

    private Optional<String> validateTransfer(WalletCard card, String fromUserId,
                                              String groupId, String toIm) {
        if (groupId != null && !groupId.isBlank()) {
            return Optional.of("WALLET_CARD_CONV_MISMATCH");
        }
        Optional<WalletTransfer> transferOpt = resolveTransfer(card);
        if (transferOpt.isEmpty()) {
            return Optional.of("WALLET_CARD_NOT_FOUND");
        }
        WalletTransfer t = transferOpt.get();
        if (!fromUserId.equals(t.getFromUserId())) {
            return Optional.of("WALLET_CARD_SENDER_MISMATCH");
        }
        String toUserId = resolveBusinessUserId(toIm);
        if (toUserId != null && !toUserId.isBlank() && !toUserId.equals(t.getToUserId())) {
            return Optional.of("WALLET_CARD_CONV_MISMATCH");
        }
        if (card.amount() != null && card.amount() != t.getAmount()) {
            return Optional.of("WALLET_CARD_PAYLOAD_MISMATCH");
        }
        if (currencyMismatch(card.currency(), t.getCurrency())) {
            return Optional.of("WALLET_CARD_PAYLOAD_MISMATCH");
        }
        return claimDedupe("c2c", c2cConversationKey(t.getFromUserId(), t.getToUserId()),
            "transfer:" + t.getId());
    }

    private Optional<String> validateRedPacket(WalletCard card, String fromUserId,
                                               String groupId, String type) {
        Optional<WalletRedPacket> packetOpt = resolveRedPacket(card);
        if (packetOpt.isEmpty()) {
            return Optional.of("WALLET_CARD_NOT_FOUND");
        }
        WalletRedPacket p = packetOpt.get();
        if (!fromUserId.equals(p.getSenderUserId())) {
            return Optional.of("WALLET_CARD_SENDER_MISMATCH");
        }

        boolean expectsGroup = isGroupConversation(p) || WalletOrderCardImSupport.TYPE_GROUP_TRANSFER.equals(type);
        if (expectsGroup) {
            if (groupId == null || groupId.isBlank()) {
                return Optional.of("WALLET_CARD_CONV_MISMATCH");
            }
            if (p.getGroupId() == null || !groupId.equals(p.getGroupId())) {
                return Optional.of("WALLET_CARD_CONV_MISMATCH");
            }
        } else {
            // C2C 红包：不应发到群
            if (groupId != null && !groupId.isBlank()) {
                return Optional.of("WALLET_CARD_CONV_MISMATCH");
            }
        }

        if (card.amount() != null && card.amount() != p.getTotalAmount()) {
            return Optional.of("WALLET_CARD_PAYLOAD_MISMATCH");
        }
        if (currencyMismatch(card.currency(), p.getCurrency())) {
            return Optional.of("WALLET_CARD_PAYLOAD_MISMATCH");
        }

        String convType = expectsGroup ? "group" : "c2c";
        String convId = expectsGroup
            ? p.getGroupId()
            : c2cConversationKey(p.getSenderUserId(),
                firstNonBlank(p.getExclusiveUserId(), card.toUserId()));
        return claimDedupe(convType, convId, "rp:" + p.getId());
    }

    private Optional<WalletRedPacket> resolveRedPacket(WalletCard card) {
        String clientId = firstNonBlank(card.clientOrderId(), card.publicId());
        if (clientId != null && !clientId.isBlank()) {
            Optional<WalletRedPacket> byPublic = redPacketRepository.findByPublicId(clientId);
            if (byPublic.isPresent()) {
                return byPublic;
            }
        }
        Long id = parseOrderId(card.orderIdRaw());
        if (id != null) {
            return redPacketRepository.findById(id);
        }
        return Optional.empty();
    }

    private Optional<WalletTransfer> resolveTransfer(WalletCard card) {
        if (card.clientOrderId() != null && !card.clientOrderId().isBlank()) {
            var rows = transferRepository.findByClientOrderId(card.clientOrderId().trim());
            if (rows != null && !rows.isEmpty()) {
                return Optional.of(rows.get(0));
            }
        }
        Long id = parseOrderId(card.orderIdRaw());
        if (id != null) {
            return transferRepository.findById(id);
        }
        return Optional.empty();
    }

    private Optional<String> claimDedupe(String convType, String convId, String orderKey) {
        if (convId == null || convId.isBlank()) {
            return Optional.of("WALLET_CARD_CONV_MISMATCH");
        }
        String redisKey = DEDUPE_KEY_PREFIX + convType + ":" + convId + ":" + orderKey;
        Boolean first = redis.opsForValue().setIfAbsent(
            redisKey, "1", Duration.ofDays(props.dedupeTtlDays()));
        if (Boolean.FALSE.equals(first)) {
            return Optional.of("WALLET_CARD_DUP");
        }
        return Optional.empty();
    }

    private boolean isAllowlistedSender(String fromIm) {
        String id = fromIm.trim();
        if (imUserIdService.isSpecialImAccount(id)) {
            return true;
        }
        for (String allow : props.allowSenderIds()) {
            if (allow != null && allow.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    private String resolveBusinessUserId(String imAccount) {
        if (imAccount == null || imAccount.isBlank()) {
            return null;
        }
        return imUserIdService.findBusinessUserId(imAccount.trim()).orElse(imAccount.trim());
    }

    private static boolean isGroupConversation(WalletRedPacket p) {
        String ct = p.getConversationType();
        if (ct != null && "GROUP".equalsIgnoreCase(ct.trim())) {
            return true;
        }
        return p.getGroupId() != null && !p.getGroupId().isBlank();
    }

    private static boolean currencyMismatch(String cardCurrency, WalletCurrency expected) {
        if (cardCurrency == null || cardCurrency.isBlank() || expected == null) {
            return false;
        }
        try {
            return WalletCurrency.fromApiCode(cardCurrency) != expected;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static Long parseOrderId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String c2cConversationKey(String a, String b) {
        if (a == null || b == null) {
            return a != null ? a : b;
        }
        String x = a.trim();
        String y = b.trim();
        return x.compareTo(y) <= 0 ? x + ":" + y : y + ":" + x;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
