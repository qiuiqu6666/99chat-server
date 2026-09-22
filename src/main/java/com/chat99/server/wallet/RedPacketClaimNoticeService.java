package com.chat99.server.wallet;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 群红包领取后向发包人发送 IM 定向群消息（To_Account=发包人）。 */
@Service
public class RedPacketClaimNoticeService {

    public static final String BUSINESS_ID = "red_packet_claim_notice";
    private static final Logger log = LoggerFactory.getLogger(RedPacketClaimNoticeService.class);

    public record ClaimNoticeCommittedEvent(WalletRedPacket packet, WalletRedPacketClaim claim) {}

    private final ApplicationEventPublisher events;
    private final WalletRedPacketClaimNoticeRepository noticeRepository;
    private final ImAdminClient imAdminClient;
    private final ImUserIdService imUserIdService;

    public RedPacketClaimNoticeService(ApplicationEventPublisher events,
                                       WalletRedPacketClaimNoticeRepository noticeRepository,
                                       ImAdminClient imAdminClient,
                                       ImUserIdService imUserIdService) {
        this.events = events;
        this.noticeRepository = noticeRepository;
        this.imAdminClient = imAdminClient;
        this.imUserIdService = imUserIdService;
    }

    public void scheduleAfterClaim(WalletRedPacket packet, WalletRedPacketClaim claim) {
        if (!isGroupGrabbable(packet)) {
            return;
        }
        events.publishEvent(new ClaimNoticeCommittedEvent(packet, claim));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClaimCommitted(ClaimNoticeCommittedEvent event) {
        sendNotice(event.packet(), event.claim());
    }

    void sendNotice(WalletRedPacket packet, WalletRedPacketClaim claim) {
        String senderUserId = packet.getSenderUserId();
        String groupId = packet.getGroupId();
        String claimerUserId = claim.getUserId();
        if (senderUserId == null || senderUserId.isBlank()
            || groupId == null || groupId.isBlank()
            || claimerUserId == null || claimerUserId.isBlank()) {
            return;
        }
        long claimTsSec = claim.getCreatedAt() == null
            ? Instant.now().getEpochSecond()
            : claim.getCreatedAt().getEpochSecond();
        String noticeId = "rpcn_" + packet.getId() + "_" + claimerUserId + "_" + claimTsSec;
        if (noticeRepository.existsByNoticeId(noticeId)
            || noticeRepository.existsByPacketIdAndClaimerUserId(packet.getId(), claimerUserId)) {
            log.debug("red packet claim notice skipped duplicate noticeId={}", noticeId);
            return;
        }

        boolean finished = packet.getRemainingCount() == 0
            && packet.getStatus() == RedPacketStatus.COMPLETED;
        String claimerName = resolveClaimerName(claimerUserId);
        String text = buildNoticeText(claimerName, finished);
        Map<String, Object> data = buildPayload(
            noticeId, packet, senderUserId, groupId, claimerUserId, claimerName, finished, text);

        int random = Math.abs(noticeId.hashCode());
        String senderIm = imUserIdService.toIm(senderUserId);
        try {
            imAdminClient.sendCustomGroup(
                senderIm,
                groupId,
                data,
                List.of(senderIm),
                text,
                random);
        } catch (ImRestException e) {
            log.warn("red packet claim notice im failed noticeId={} packetId={} err={}",
                noticeId, packet.getId(), e.getMessage());
            return;
        }

        WalletRedPacketClaimNotice row = new WalletRedPacketClaimNotice();
        row.setNoticeId(noticeId);
        row.setPacketId(packet.getId());
        row.setSenderUserId(senderUserId);
        row.setGroupId(groupId);
        row.setClaimerUserId(claimerUserId);
        row.setShowFinishedSuffix(finished);
        try {
            noticeRepository.save(row);
            log.info("red packet claim notice sent noticeId={} packetId={} sender={}",
                noticeId, packet.getId(), senderUserId);
        } catch (Exception e) {
            log.warn("red packet claim notice save failed noticeId={}: {}", noticeId, e.getMessage());
        }
    }

    public static boolean isGroupGrabbable(WalletRedPacket packet) {
        if (packet == null || !"GROUP".equals(packet.getConversationType())) {
            return false;
        }
        RedPacketType type = packet.getPacketType();
        return type == RedPacketType.NORMAL_GROUP || type == RedPacketType.LUCKY_GROUP;
    }

    private static Map<String, Object> buildPayload(String noticeId, WalletRedPacket packet,
                                                    String senderUserId, String groupId,
                                                    String claimerUserId, String claimerName,
                                                    boolean finished, String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("businessID", BUSINESS_ID);
        data.put("version", 1);
        data.put("noticeId", noticeId);
        data.put("packetId", String.valueOf(packet.getId()));
        data.put("senderUserId", senderUserId);
        data.put("groupId", groupId);
        data.put("claimerUserId", claimerUserId);
        data.put("claimerName", claimerName);
        data.put("showFinishedSuffix", finished);
        data.put("text", text);
        return data;
    }

    static String buildNoticeText(String claimerName, boolean finished) {
        String name = claimerName == null || claimerName.isBlank() ? "好友" : claimerName;
        if (finished) {
            return name + "领取了你的红包，你的红包已被领完";
        }
        return name + "领取了你的红包";
    }

    private String resolveClaimerName(String userId) {
        try {
            String imAccount = imUserIdService.toIm(userId);
            Map<String, ImAdminClient.ProfilePortrait> profiles =
                imAdminClient.getPortraitProfiles(Set.of(imAccount));
            ImAdminClient.ProfilePortrait profile = profiles.get(imAccount);
            return profile == null ? null : profile.nickname();
        } catch (Exception e) {
            log.debug("claim notice nick resolve failed userId={}: {}", userId, e.getMessage());
            return null;
        }
    }
}
