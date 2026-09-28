package com.chat99.server.wallet;

import com.chat99.server.group.GroupAccessService;
import com.chat99.server.group.GroupMemberRepository;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Authoritative business cards. Availability does not depend on IM delivery. */
@RestController
@ConditionalOnProperty(name = "wallet.proxy-enabled", havingValue = "false", matchIfMissing = true)
public class WalletConversationCards {
    private final WalletCardOutboxRepository cards;
    private final WalletRedPacketRepository packets;
    private final WalletRedPacketClaimRepository claims;
    private final GroupAccessService groups;
    private final GroupMemberRepository members;
    private final ObjectMapper json;
    private final ImUserIdService imUsers;

    public WalletConversationCards(WalletCardOutboxRepository cards, WalletRedPacketRepository packets,
        WalletRedPacketClaimRepository claims, GroupAccessService groups, GroupMemberRepository members,
        ObjectMapper json, ImUserIdService imUsers) {
        this.cards = cards; this.packets = packets; this.claims = claims;
        this.groups = groups; this.members = members; this.json = json;
        this.imUsers = imUsers;
    }

    @GetMapping("/wallet/card-orders/conversation")
    @Transactional(readOnly = true)
    public Map<String, Object> list(Authentication auth, @RequestParam String target,
        @RequestParam(defaultValue = "false") boolean group,
        @RequestParam(required = false) String before, @RequestParam(defaultValue = "") String beforeId) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String viewer = auth.getName();
        if (viewer == null || viewer.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (target == null || target.isBlank() || target.length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Instant joined = Instant.EPOCH;
        if (group) {
            if (!groups.isLocalMember(target, viewer)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            var member = members.findByGroupIdAndUserIdActive(target, viewer)
                .or(() -> Optional.ofNullable(imUsers.toIm(viewer))
                    .flatMap(imId -> members.findByGroupIdAndUserIdActive(target, imId)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
            if (member.getJoinedAt() != null) joined = member.getJoinedAt();
        }
        Instant upper;
        try { upper = before == null ? Instant.now().plusSeconds(1) : Instant.parse(before); }
        catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR"); }
        var rows = cards.conversationCards(viewer, target, group, joined, upper, beforeId, PageRequest.of(0, 51));
        var visible = rows.subList(0, Math.min(50, rows.size()));
        Map<Long, WalletRedPacket> packetMap = new HashMap<>();
        List<Long> packetIds = visible.stream().filter(r -> r.getId().startsWith("rp:"))
            .map(r -> Long.parseLong(r.getId().substring(3))).toList();
        if (!packetIds.isEmpty()) packets.findAllById(packetIds).forEach(p -> packetMap.put(p.getId(), p));
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : visible) result.add(snapshot(row, packetMap));
        Map<String, Object> page = new LinkedHashMap<>(); page.put("cards", result);
        if (rows.size() > 50) {
            var last = visible.get(visible.size() - 1);
            page.put("nextBefore", last.getCreatedAt().toString()); page.put("nextBeforeId", last.getId());
        }
        return page;
    }

    private Map<String, Object> snapshot(WalletCardOutbox row, Map<Long, WalletRedPacket> packetMap) {
        try {
            String payload = row.getPayload();
            if (row.getId().startsWith("rp:")) {
                var packet = Objects.requireNonNull(packetMap.get(Long.parseLong(row.getId().substring(3))), "Missing wallet order");
                long claimed = packet.getStatus() == RedPacketStatus.REFUNDED || packet.getStatus() == RedPacketStatus.EXPIRED
                    ? claims.countByPacketId(packet.getId()) : Math.max(0, packet.getPacketCount() - packet.getRemainingCount());
                payload = WalletCardStateSync.snapshot(payload, packet, claimed, json);
            }
            Map<String, Object> data = json.readValue(payload, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            data.put("cardId", row.getId()); data.put("serverManagedCard", true);
            data.put("cardDeliveryState", row.getState()); data.put("paymentState", "COMMITTED");
            data.put("createdAt", row.getCreatedAt().toString()); data.put("timestamp", row.getCreatedAt().getEpochSecond());
            return data;
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid card", e); }
    }
}
