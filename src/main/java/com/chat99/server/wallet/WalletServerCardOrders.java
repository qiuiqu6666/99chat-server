package com.chat99.server.wallet;

import com.chat99.server.user.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Versioned entry point: legacy endpoints never acquire server card ownership. */
@Service
public class WalletServerCardOrders {
    private final WalletTransferService transfers;
    private final RedPacketService packets;
    private final WalletTransferRepository transferRepository;
    private final WalletRedPacketRepository packetRepository;
    private final WalletCardOutboxRepository outbox;
    private final WalletPartyNameResolver profiles;
    private final EntityManager em;
    private final ObjectMapper json;

    public WalletServerCardOrders(WalletTransferService transfers, RedPacketService packets,
        WalletTransferRepository transferRepository, WalletRedPacketRepository packetRepository,
        WalletCardOutboxRepository outbox, WalletPartyNameResolver profiles, EntityManager em, ObjectMapper json) {
        this.transfers = transfers; this.packets = packets; this.transferRepository = transferRepository;
        this.packetRepository = packetRepository; this.outbox = outbox; this.profiles = profiles;
        this.em = em; this.json = json;
    }

    @Transactional
    public Map<String, Object> transfer(String sender, WalletController.TransferRequest req) {
        String cid = requireClientId(req.clientOrderId());
        lockSender(sender);
        String hash = hash(Arrays.asList("transfer", req.toUserId(), req.currency(), req.amount(), req.memo()));
        var existing = outbox.findBySenderIdAndClientId(sender, cid);
        if (existing.isPresent()) return replay(existing.get(), hash);
        // Never adopt a legacy order: its client may already have delivered the card.
        if (transferRepository.findByFromUserIdAndClientOrderId(sender, cid).isPresent()) {
            throw WalletExceptions.of(HttpStatus.CONFLICT, "LEGACY_CARD_ORDER");
        }
        var t = transfers.transfer(sender, req.toUserId(), req.currency(), req.amount(), req.payPin(), req.memo(), cid);
        var card = base("wallet_transfer", t.getId(), cid, sender, t.getToUserId(), false,
            t.getCurrency(), t.getAmount(), "success", t.getMemo());
        parties(card, sender, t.getToUserId());
        return enqueue("transfer:" + t.getId(), sender, cid, t.getToUserId(), false, hash, card);
    }

    @Transactional
    public Map<String, Object> redPacket(String sender, WalletController.RedPacketSendRequest req) {
        String cid = requireClientId(req.clientPacketId());
        lockSender(sender);
        String hash = hash(Arrays.asList("red_packet", req.packetType(), req.conversationType(), req.groupId(),
            req.toUserId(), req.currency(), req.totalAmount(), req.perAmount(), req.packetCount(), req.greeting()));
        var existing = outbox.findBySenderIdAndClientId(sender, cid);
        if (existing.isPresent()) return replay(existing.get(), hash);
        if (packetRepository.existsByPublicId(cid)) throw WalletExceptions.of(HttpStatus.CONFLICT, "LEGACY_CARD_ORDER");
        var p = packets.send(sender, req.packetType(), req.conversationType(), req.groupId(), req.toUserId(),
            req.currency(), req.totalAmount() == null ? 0 : req.totalAmount(), req.perAmount(),
            req.packetCount() == null ? 1 : req.packetCount(), req.greeting(), req.payPin(), cid);
        boolean group = p.isGroupConversation();
        String target = group ? p.getGroupId() : p.getExclusiveUserId();
        String type = p.getPacketType() == RedPacketType.GROUP_TRANSFER ? "wallet_group_transfer" : "wallet_red_packet";
        var card = base(type, p.getId(), cid, sender, target, group, p.getCurrency(), p.getTotalAmount(),
            p.getStatus() == RedPacketStatus.ACTIVE ? "pending" : "success", p.getGreeting());
        card.put("publicId", p.getPublicId());
        card.put("packetType", p.getPacketType().name());
        card.put("packetCount", p.getPacketCount());
        if (group) card.put("groupId", target);
        parties(card, sender, p.getExclusiveUserId());
        return enqueue("rp:" + p.getId(), sender, cid, target, group, hash, card);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String sender, String clientId) {
        var row = outbox.findBySenderIdAndClientId(sender, clientId)
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
        return view(row);
    }

    private void lockSender(String sender) {
        var users = em.createQuery("select u from User u where u.userId = :id", User.class)
            .setParameter("id", sender).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (users.isEmpty()) throw WalletExceptions.of(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND");
    }

    private Map<String, Object> replay(WalletCardOutbox row, String hash) {
        if (!row.getRequestHash().equals(hash)) throw WalletExceptions.of(HttpStatus.CONFLICT, "CLIENT_ORDER_ID_CONFLICT");
        return view(row);
    }

    private Map<String, Object> enqueue(String id, String sender, String cid, String target, boolean group,
        String hash, Map<String, Object> card) {
        if (target == null || target.isBlank()) throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "INVALID_RECIPIENT");
        WalletCardOutbox row = new WalletCardOutbox();
        row.setId(id); row.setSenderId(sender); row.setClientId(cid); row.setTargetId(target);
        row.setGroupMessage(group); row.setRequestHash(hash);
        row.setImRandom(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
        card.put("cardId", id);
        row.setPayload(encode(card));
        outbox.saveAndFlush(row); // A failure rolls back the debit/credit as well.
        return view(row);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> view(WalletCardOutbox row) {
        try {
            Map<String, Object> result = new LinkedHashMap<>(json.readValue(row.getPayload(), Map.class));
            result.put("serverManagedCard", true);
            result.put("cardDeliveryState", row.getState());
            result.put("paymentState", "COMMITTED");
            result.put("cardId", row.getId());
            result.put("cardDeliveryError", row.getLastError());
            // An ACTIVE red packet is a successful payment; claiming is a separate operation.
            result.put("status", "COMPLETED");
            return result;
        } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid wallet card payload", e); }
    }

    private Map<String, Object> base(String type, Long id, String cid, String sender, String target,
        boolean group, WalletCurrency currency, long amount, String status, String greeting) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("businessID", "wallet_order"); card.put("version", 1);
        card.put("type", type); card.put("customType", type);
        card.put("id", String.valueOf(id)); card.put("orderId", String.valueOf(id)); card.put("clientOrderId", cid);
        card.put("senderUserId", sender); card.put("fromUserId", sender);
        card.put("conversationId", target); card.put("isGroup", group);
        card.put("currency", currency.getApiCode()); card.put("amount", amount); card.put("status", status);
        card.put("greeting", greeting == null ? "" : greeting); card.put("memo", greeting == null ? "" : greeting);
        return card;
    }

    private void parties(Map<String, Object> card, String sender, String receiver) {
        List<String> ids = new ArrayList<>(); ids.add(sender);
        if (receiver != null && !receiver.isBlank()) {
            ids.add(receiver); card.put("receiverId", receiver); card.put("toUserId", receiver);
        }
        var parties = profiles.resolveProfiles(ids);
        var from = parties.get(sender);
        if (from != null) { card.put("senderName", from.nickname()); card.put("senderAvatar", from.avatarUrl()); }
        var to = parties.get(receiver);
        if (to != null) { card.put("receiverName", to.nickname()); card.put("receiverAvatar", to.avatarUrl()); }
    }

    private static String requireClientId(String id) {
        if (id == null || id.isBlank() || id.length() > 64) throw WalletExceptions.of(HttpStatus.BAD_REQUEST, "CLIENT_ORDER_ID_REQUIRED");
        return id.trim();
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private String hash(Object fields) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(fields).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
