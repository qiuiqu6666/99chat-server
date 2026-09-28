package com.chat99.server.wallet;

import com.chat99.server.group.GroupAccessService;
import com.chat99.server.group.GroupMemberRedisSet;
import com.chat99.server.group.GroupMemberRepository;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RedPacketService {

    private static final Logger log = LoggerFactory.getLogger(RedPacketService.class);

    /** 客户端 / IM 卡片用字符串 ID：red_packet_{uuid} */
    private static final Pattern PUBLIC_ID =
        Pattern.compile("(?i)^red_packet_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final WalletRedPacketRepository packetRepository;
    private final WalletRedPacketClaimRepository claimRepository;
    private final WalletLedgerService ledgerService;
    private final PayPinService payPinService;
    private final WalletLimitService limitService;
    private final WalletFeeService feeService;
    private final WalletConfigService configService;
    private final UserRepository userRepository;
    private final WalletPlatformStatsRepository statsRepository;
    private final ImAdminClient imAdminClient;
    private final ImUserIdService imUserIdService;
    private final GroupAccessService groupAccessService;
    private final PlatformWalletNoticeService platformWalletNotice;
    private final RedPacketClaimNoticeService claimNoticeService;
    private final WalletOrderCardReadCache cardReadCache;
    private final TransactionTemplate transactionTemplate;
    private final RedPacketClaimStateCache claimStateCache;
    private final RedPacketGrabStore grabStore;
    private final GroupMemberRedisSet groupMemberRedisSet;
    private final GroupMemberRepository groupMemberRepository;

    public RedPacketService(WalletRedPacketRepository packetRepository,
                            WalletRedPacketClaimRepository claimRepository,
                            WalletLedgerService ledgerService, PayPinService payPinService,
                            WalletLimitService limitService, WalletFeeService feeService,
                            WalletConfigService configService, UserRepository userRepository,
                            WalletPlatformStatsRepository statsRepository,
                            ImAdminClient imAdminClient,
                            ImUserIdService imUserIdService,
                            GroupAccessService groupAccessService,
                            PlatformWalletNoticeService platformWalletNotice,
                            RedPacketClaimNoticeService claimNoticeService,
                            WalletOrderCardReadCache cardReadCache,
                            @Nullable PlatformTransactionManager transactionManager,
                            @Nullable RedPacketClaimStateCache claimStateCache,
                            @Nullable RedPacketGrabStore grabStore,
                            @Nullable GroupMemberRedisSet groupMemberRedisSet,
                            @Nullable GroupMemberRepository groupMemberRepository) {
        this.packetRepository = packetRepository;
        this.claimRepository = claimRepository;
        this.ledgerService = ledgerService;
        this.payPinService = payPinService;
        this.limitService = limitService;
        this.feeService = feeService;
        this.configService = configService;
        this.userRepository = userRepository;
        this.statsRepository = statsRepository;
        this.imAdminClient = imAdminClient;
        this.imUserIdService = imUserIdService;
        this.groupAccessService = groupAccessService;
        this.platformWalletNotice = platformWalletNotice;
        this.claimNoticeService = claimNoticeService;
        this.cardReadCache = cardReadCache;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.claimStateCache = claimStateCache;
        this.grabStore = grabStore;
        this.groupMemberRedisSet = groupMemberRedisSet;
        this.groupMemberRepository = groupMemberRepository;
    }

    @Transactional
    public WalletRedPacket send(String senderUserId, RedPacketType type, String conversationType,
                                String groupId, String exclusiveUserId, WalletCurrency currency,
                                long totalAmount, Long perAmount, int packetCount, String greeting,
                                String payPin) {
        return send(senderUserId, type, conversationType, groupId, exclusiveUserId, currency,
            totalAmount, perAmount, packetCount, greeting, payPin, null);
    }

    @Transactional
    public WalletRedPacket send(String senderUserId, RedPacketType type, String conversationType,
                                String groupId, String exclusiveUserId, WalletCurrency currency,
                                long totalAmount, Long perAmount, int packetCount, String greeting,
                                String payPin, String clientPacketId) {
        payPinService.requireSetAndVerify(senderUserId, payPin);
        validateSend(type, conversationType, groupId, exclusiveUserId, totalAmount, perAmount, packetCount);
        String effectiveGroupId = groupId;
        if ("GROUP".equalsIgnoreCase(conversationType)) {
            String normalizedGroupId = groupAccessService.normalizeGroupId(groupId);
            if (normalizedGroupId != null && !normalizedGroupId.isBlank()) {
                effectiveGroupId = normalizedGroupId;
            }
        }
        if (type == RedPacketType.GROUP_TRANSFER) {
            requireLocalGroupMember(effectiveGroupId, senderUserId);
            requireLocalGroupMember(effectiveGroupId, exclusiveUserId);
        } else if ("GROUP".equals(conversationType)) {
            requireGroupMember(effectiveGroupId, senderUserId);
        }

        long charge = computeCharge(type, totalAmount, perAmount, packetCount);
        limitService.check(senderUserId, WalletLimitScene.RED_PACKET, currency, charge);
        long fee = feeService.calculateFee(WalletFeeScene.RED_PACKET_SEND, currency, charge);

        WalletRedPacket packet = new WalletRedPacket();
        packet.setPublicId(resolvePublicIdForCreate(clientPacketId));
        packet.setSenderUserId(senderUserId);
        packet.setPacketType(type);
        packet.setConversationType(conversationType);
        if (type == RedPacketType.NORMAL_C2C || !"GROUP".equalsIgnoreCase(conversationType)) {
            packet.setGroupId(null);
        } else {
            packet.setGroupId(effectiveGroupId);
        }
        packet.setExclusiveUserId(exclusiveUserId);
        packet.setCurrency(currency);
        packet.setTotalAmount(charge);
        packet.setPacketCount(isDirectCredit(type) ? 1 : packetCount);
        packet.setPerAmount(perAmount);
        packet.setGreeting(greeting);

        if (isDirectCredit(type)) {
            String to = exclusiveUserId;
            if (!userRepository.existsByUserId(to)) {
                throw WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND");
            }
            packet.setRemainingAmount(0);
            packet.setRemainingCount(0);
            packet.setStatus(RedPacketStatus.PROCESSING);
            packetRepository.save(packet);
            rememberPacket(packet);
            debitSend(senderUserId, currency, charge, fee, packet.getId(), exclusiveUserId, greeting);
            creditClaim(packet, to, charge);
            limitService.addDaily(senderUserId, WalletLimitScene.RED_PACKET, currency, charge + fee);
            packet.setStatus(RedPacketStatus.COMPLETED);
            packetRepository.save(packet);
            rememberPacket(packet);
            return packet;
        }

        packet.setRemainingAmount(charge);
        packet.setRemainingCount(packetCount);
        packet.setStatus(RedPacketStatus.PROCESSING);
        if ("GROUP".equals(conversationType)) {
            packet.setExpiresAt(Instant.now().plus(configService.getRedPacketExpireHours(), ChronoUnit.HOURS));
        }
        packetRepository.save(packet);
        rememberPacket(packet);
        debitSend(senderUserId, currency, charge, fee, packet.getId(), exclusiveUserId, greeting);
        limitService.addDaily(senderUserId, WalletLimitScene.RED_PACKET, currency, charge + fee);
        packet.setStatus(RedPacketStatus.ACTIVE);
        packetRepository.save(packet);
        rememberPacket(packet);
        scheduleClaimStateInit(packet);
        return packet;
    }

    public List<RedPacketGrabStore.PendingClaim> pendingClaims(long packetId) {
        if (grabStore == null) {
            return List.of();
        }
        try {
            return grabStore.pending(packetId);
        } catch (RuntimeException e) {
            log.warn("red packet pending read failed packetId={}: {}", packetId, e.getMessage());
            return List.of();
        }
    }

    public WalletRedPacketClaim claim(String userId, String packetIdRef) {
        if (!isNumericId(packetIdRef)) {
            WalletRedPacket packet = requirePacket(packetIdRef);
            return claim(userId, String.valueOf(packet.getId()));
        }
        if (grabStore == null) {
            throw WalletExceptions.of(HttpStatus.GONE, "RED_PACKET_EXPIRED");
        }
        long packetId = Long.parseLong(packetIdRef.trim());
        RedPacketClaimResult result = grabStore.claim(packetId, userId);
        if (result.claimed() && !RedPacketClaimResult.ALREADY.equals(result.status())) {
            WalletRedPacketClaim claim = new WalletRedPacketClaim();
            claim.setId(parseClaimId(result.claimId()));
            claim.setPacketId(packetId);
            claim.setUserId(userId);
            claim.setAmount(result.amount() == null ? 0L : result.amount());
            claim.setCreatedAt(Instant.now());
            return claim;
        }
        throw legacyClaimStatus(result.status());
    }

    private void ensureArmed(long packetId) {
        if (grabStore.armed(packetId)) {
            return;
        }
        synchronized (("rp-arm-" + packetId).intern()) {
            if (grabStore.armed(packetId)) {
                return;
            }
            WalletRedPacket packet = packetRepository.findById(packetId).orElse(null);
            if (packet == null || packet.getStatus() != RedPacketStatus.ACTIVE || packet.getRemainingCount() <= 0) {
                return;
            }
            WalletRedPacket slice = new WalletRedPacket();
            slice.setId(packet.getId());
            slice.setSenderUserId(packet.getSenderUserId());
            slice.setGroupId(packet.getGroupId());
            slice.setCurrency(packet.getCurrency());
            slice.setPacketType(packet.getPacketType());
            slice.setPerAmount(packet.getPerAmount());
            slice.setExpiresAt(packet.getExpiresAt());
            slice.setTotalAmount(packet.getRemainingAmount());
            slice.setPacketCount(packet.getRemainingCount());
            Map<String, String> already = new LinkedHashMap<>();
            for (WalletRedPacketClaim row : claimRepository.findByPacketIdOrderByCreatedAtAsc(packetId)) {
                already.put(row.getUserId(), row.getId() + "|" + row.getAmount() + "|" + RedPacketClaimResult.CREDITED);
            }
            grabStore.arm(slice, splitForGrab(slice), already);
            if (groupMemberRedisSet != null && groupMemberRepository != null
                && packet.getGroupId() != null && !packet.getGroupId().isBlank()) {
                groupMemberRedisSet.addAll(packet.getGroupId(),
                    groupMemberRepository.findActiveUserIdsByGroupId(packet.getGroupId()));
            }
        }
    }

    private static ResponseStatusException legacyClaimStatus(String status) {
        if (RedPacketClaimResult.NOT_MEMBER.equals(status)) {
            return WalletExceptions.of(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
        }
        if (RedPacketClaimResult.ALREADY.equals(status)) {
            return WalletExceptions.of(HttpStatus.CONFLICT, "ALREADY_CLAIMED");
        }
        if (RedPacketClaimResult.EMPTY.equals(status)) {
            return WalletExceptions.of(HttpStatus.GONE, "RED_PACKET_EMPTY");
        }
        return WalletExceptions.of(HttpStatus.GONE, "RED_PACKET_EXPIRED");
    }

    @Transactional
    public void expireRefund(WalletRedPacket packet) {
        if (packet == null || packet.getId() == null) {
            return;
        }
        WalletRedPacket locked = packetRepository.findByIdForUpdate(packet.getId()).orElse(null);
        if (locked == null || locked.getStatus() != RedPacketStatus.ACTIVE) {
            return;
        }
        long refund = locked.getRemainingAmount();
        if (refund > 0) {
            ledgerService.credit(locked.getSenderUserId(), locked.getCurrency(), refund,
                WalletLedgerType.RED_PACKET_REFUND, "RED_PACKET", locked.getId(), null, "expired");
        }
        locked.setRemainingAmount(0);
        locked.setRemainingCount(0);
        locked.setStatus(RedPacketStatus.REFUNDED);
        packetRepository.save(locked);
        evictClaimState(locked.getId());
        platformWalletNotice.notifyRedPacketRefund(locked, refund);
    }

    private WalletRedPacketClaim persistClaim(String userId, String packetIdRef) {
        if (transactionTemplate == null) {
            return persistClaimLocked(userId, packetIdRef);
        }
        return transactionTemplate.execute(status -> persistClaimLocked(userId, packetIdRef));
    }

    private WalletRedPacketClaim persistClaimLocked(String userId, String packetIdRef) {
        WalletRedPacket packet = requirePacketForUpdate(packetIdRef);
        long packetId = packet.getId();
        if (packet.getStatus() != RedPacketStatus.ACTIVE) {
            throw WalletExceptions.of(HttpStatus.GONE, "RED_PACKET_EXPIRED");
        }
        if (packet.getRemainingCount() <= 0) {
            throw WalletExceptions.of(HttpStatus.GONE, "RED_PACKET_EMPTY");
        }
        if (claimRepository.existsByPacketIdAndUserId(packetId, userId)) {
            throw WalletExceptions.of(HttpStatus.CONFLICT, "ALREADY_CLAIMED");
        }
        long amount = nextClaimAmount(packet);
        try {
            creditClaim(packet, userId, amount);
        } catch (DataIntegrityViolationException e) {
            throw WalletExceptions.of(HttpStatus.CONFLICT, "ALREADY_CLAIMED");
        }
        packet.setRemainingAmount(packet.getRemainingAmount() - amount);
        packet.setRemainingCount(packet.getRemainingCount() - 1);
        if (packet.getRemainingCount() == 0) {
            packet.setStatus(RedPacketStatus.COMPLETED);
            evictClaimState(packetId);
        }
        packetRepository.save(packet);
        rememberPacket(packet);
        WalletRedPacketClaim claim = claimRepository.findByPacketIdAndUserId(packetId, userId).orElseThrow();
        claimNoticeService.scheduleAfterClaim(packet, claim);
        return claim;
    }

    private RedPacketClaimStateCache.GrabResult tryGrabSlot(WalletRedPacket packet, String userId) {
        if (claimStateCache == null || !claimStateCache.available()) {
            return RedPacketClaimStateCache.GrabResult.UNAVAILABLE;
        }
        RedPacketClaimStateCache.GrabResult grab = claimStateCache.tryGrab(packet.getId(), userId);
        if (grab == RedPacketClaimStateCache.GrabResult.MISSING) {
            warmClaimState(packet);
            grab = claimStateCache.tryGrab(packet.getId(), userId);
        }
        return grab;
    }

    private void armGrab(WalletRedPacket packet) {
        if (grabStore == null) {
            return;
        }
        List<Long> amounts = splitForGrab(packet);
        grabStore.arm(packet, amounts, Map.of());
        if (groupMemberRedisSet != null && groupMemberRepository != null
            && packet.getGroupId() != null && !packet.getGroupId().isBlank()) {
            groupMemberRedisSet.addAll(packet.getGroupId(),
                groupMemberRepository.findActiveUserIdsByGroupId(packet.getGroupId()));
        }
    }

    private static List<Long> splitForGrab(WalletRedPacket packet) {
        int count = packet.getPacketCount();
        long total = packet.getTotalAmount();
        if (packet.getPacketType() == RedPacketType.NORMAL_GROUP && packet.getPerAmount() != null) {
            List<Long> parts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                parts.add(packet.getPerAmount());
            }
            return parts;
        }
        return new ArrayList<>(RedPacketSplitUtils.luckySplit(
            total, count, RedPacketSplitUtils.MIN_UNIT, ThreadLocalRandom.current()));
    }

    private void scheduleClaimStateInit(WalletRedPacket packet) {
        if (packet.getId() == null || isDirectCredit(packet.getPacketType())) {
            return;
        }
        Runnable init = () -> armGrab(packet);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            init.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                init.run();
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    evictClaimState(packet.getId());
                }
            }
        });
    }

    private void warmClaimState(WalletRedPacket packet) {
        List<String> claimed = claimRepository.findByPacketIdOrderByCreatedAtAsc(packet.getId())
            .stream()
            .map(WalletRedPacketClaim::getUserId)
            .toList();
        claimStateCache.init(
            packet.getId(),
            packet.getRemainingCount(),
            RedPacketClaimStateCache.ttlSeconds(packet),
            claimed);
    }

    private void evictClaimState(long packetId) {
        if (claimStateCache != null) {
            claimStateCache.evict(packetId);
        }
    }

    private static boolean shouldReleaseGrab(String reason) {
        return "ALREADY_CLAIMED".equals(reason) || "RED_PACKET_INVALID_STATE".equals(reason);
    }

    private long nextClaimAmount(WalletRedPacket packet) {
        if (packet.getPacketType() == RedPacketType.NORMAL_GROUP && packet.getPerAmount() != null) {
            return packet.getPerAmount();
        }
        long remain = packet.getRemainingAmount();
        int remainCount = packet.getRemainingCount();
        try {
            return RedPacketSplitUtils.drawLuckyAmount(remain, remainCount, RedPacketSplitUtils.MIN_UNIT);
        } catch (IllegalArgumentException e) {
            throw WalletExceptions.of(HttpStatus.CONFLICT, "RED_PACKET_INVALID_STATE");
        }
    }

    private void creditClaim(WalletRedPacket packet, String userId, long amount) {
        WalletRedPacketClaim claim = new WalletRedPacketClaim();
        claim.setPacketId(packet.getId());
        claim.setUserId(userId);
        claim.setAmount(amount);
        claimRepository.save(claim);
        ledgerService.credit(userId, packet.getCurrency(), amount, WalletLedgerType.RED_PACKET_RECEIVE,
            "RED_PACKET", packet.getId(), packet.getSenderUserId(), null);
    }

    private static boolean isDirectCredit(RedPacketType type) {
        return type == RedPacketType.EXCLUSIVE
            || type == RedPacketType.NORMAL_C2C
            || type == RedPacketType.GROUP_TRANSFER;
    }

    private static long computeCharge(RedPacketType type, long total, Long perAmount, int count) {
        return switch (type) {
            case NORMAL_GROUP -> perAmount * count;
            case LUCKY_GROUP -> total;
            case EXCLUSIVE, NORMAL_C2C, GROUP_TRANSFER -> total;
        };
    }

    private static void validateSend(RedPacketType type, String conversationType, String groupId,
                                     String exclusiveUserId, long total, Long perAmount, int count) {
        if (type == RedPacketType.NORMAL_C2C && !"C2C".equals(conversationType)) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (type == RedPacketType.GROUP_TRANSFER) {
            if (!"GROUP".equals(conversationType)
                || groupId == null || groupId.isBlank()
                || exclusiveUserId == null || exclusiveUserId.isBlank()
                || total <= 0) {
                throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            return;
        }
        if ((type == RedPacketType.EXCLUSIVE || type == RedPacketType.NORMAL_C2C)
            && (exclusiveUserId == null || exclusiveUserId.isBlank())) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (type == RedPacketType.NORMAL_GROUP && (perAmount == null || perAmount <= 0 || count <= 0)) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (type == RedPacketType.LUCKY_GROUP) {
            try {
                RedPacketSplitUtils.validateLuckySend(total, count);
            } catch (IllegalArgumentException e) {
                throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
        }
        if ((type == RedPacketType.NORMAL_GROUP || type == RedPacketType.LUCKY_GROUP)
            && !"GROUP".equals(conversationType)) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    public boolean canViewPacket(WalletRedPacket packet, String userId) {
        if (packet.getSenderUserId() != null && packet.getSenderUserId().equals(userId)) {
            return true;
        }
        if (userId != null && userId.equals(packet.getExclusiveUserId())) {
            return true;
        }
        if (packet.getId() != null && claimRepository.existsByPacketIdAndUserId(packet.getId(), userId)) {
            return true;
        }
        if (isGroupPacket(packet)) {
            return isPacketGroupMemberLenient(packet, userId);
        }
        return false;
    }

    /**
     * 卡片只读：发送人 / 收款人 / 群成员放行。
     * 群成员投影延迟时不 403，避免刚发出后短暂失败。
     */
    public WalletRedPacket requireReadablePacket(String packetIdRef, String userId) {
        WalletRedPacket packet = requirePacket(packetIdRef);
        if (canViewPacket(packet, userId)) {
            return packet;
        }
        if (isGroupPacket(packet)) {
            return packet;
        }
        throw WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    public WalletRedPacket requireViewablePacket(long packetId, String userId) {
        return requireReadablePacket(String.valueOf(packetId), userId);
    }

    public WalletRedPacket requirePacket(long packetId) {
        return packetRepository.findById(packetId)
            .or(() -> cardReadCache.findPacketById(packetId))
            .orElseThrow(() -> WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    public WalletRedPacket requirePacket(String packetIdRef) {
        return resolvePacket(packetIdRef);
    }

    public WalletRedPacket requirePacketForUpdate(String packetIdRef) {
        String ref = normalizePacketIdRef(packetIdRef);
        if (isNumericId(ref)) {
            return packetRepository.findByIdForUpdate(Long.parseLong(ref))
                .orElseThrow(() -> WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND"));
        }
        return packetRepository.findByPublicIdForUpdate(ref)
            .orElseThrow(() -> WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    public WalletRedPacket resolvePacket(String packetIdRef) {
        if (packetIdRef == null || packetIdRef.isBlank()) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        String ref = packetIdRef.trim();
        if (PUBLIC_ID.matcher(ref).matches()) {
            String publicId = ref.toLowerCase(Locale.ROOT);
            return packetRepository.findByPublicId(publicId)
                .or(() -> cardReadCache.findPacketByClientOrderId(publicId))
                .orElseThrow(() -> WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND"));
        }
        if (isNumericId(ref)) {
            return requirePacket(Long.parseLong(ref));
        }
        String clientId = ref.toLowerCase(Locale.ROOT);
        return packetRepository.findByPublicId(clientId)
            .or(() -> packetRepository.findByPublicId(ref))
            .or(() -> cardReadCache.findPacketByClientOrderId(clientId))
            .orElseThrow(() -> WalletExceptions.of(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    private void rememberPacket(WalletRedPacket packet) {
        cardReadCache.putPacket(packet);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    cardReadCache.evictPacket(packet);
                } else {
                    cardReadCache.putPacket(packet);
                }
            }
        });
    }

    private String resolvePublicIdForCreate(String clientPacketId) {
        if (clientPacketId != null && !clientPacketId.isBlank()) {
            String publicId = clientPacketId.trim();
            if (!PUBLIC_ID.matcher(publicId).matches()) {
                throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_PACKET_ID");
            }
            publicId = publicId.toLowerCase(Locale.ROOT);
            if (packetRepository.existsByPublicId(publicId)) {
                throw WalletExceptions.of(org.springframework.http.HttpStatus.CONFLICT, "PACKET_ID_EXISTS");
            }
            return publicId;
        }
        return "red_packet_" + UUID.randomUUID();
    }

    private static String normalizePacketIdRef(String packetIdRef) {
        if (packetIdRef == null || packetIdRef.isBlank()) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String ref = packetIdRef.trim();
        if (PUBLIC_ID.matcher(ref).matches()) {
            return ref.toLowerCase(Locale.ROOT);
        }
        if (isNumericId(ref)) {
            return ref;
        }
        throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
    }

    private static boolean isNumericId(String ref) {
        if (ref == null || ref.isEmpty() || ref.length() > 18) {
            return false;
        }
        for (int i = 0; i < ref.length(); i++) {
            if (!Character.isDigit(ref.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isGroupPacket(WalletRedPacket packet) {
        return "GROUP".equals(packet.getConversationType());
    }

    private void requireGroupMemberIfGroupPacket(WalletRedPacket packet, String userId) {
        if (isGroupPacket(packet)) {
            if (packet.getPacketType() == RedPacketType.GROUP_TRANSFER) {
                requireLocalGroupMember(packet.getGroupId(), userId);
            } else {
                requireGroupMember(packet.getGroupId(), userId);
            }
        }
    }

    private void requireGroupMember(String groupId, String userId) {
        if (groupId == null || groupId.isBlank()) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (!isGroupMember(groupId, userId)) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
        }
    }

    private void requireLocalGroupMember(String groupId, String userId) {
        if (groupId == null || groupId.isBlank()) {
            throw WalletExceptions.of(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        try {
            if (!groupAccessService.isLocalMember(groupId, userId)) {
                throw WalletExceptions.of(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
            }
        } catch (ResponseStatusException e) {
            if ("GROUP_NOT_FOUND".equals(e.getReason())) {
                throw WalletExceptions.of(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
            }
            if ("NOT_GROUP_MEMBER".equals(e.getReason())) {
                throw WalletExceptions.of(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
            }
            throw e;
        }
    }

    private boolean isPacketGroupMember(WalletRedPacket packet, String userId) {
        if (packet.getPacketType() == RedPacketType.GROUP_TRANSFER) {
            return isLocalGroupMember(packet.getGroupId(), userId);
        }
        return isGroupMember(packet.getGroupId(), userId);
    }

    /** 读卡：成员表/IM 延迟时当作可看，避免刚发出 403。 */
    private boolean isPacketGroupMemberLenient(WalletRedPacket packet, String userId) {
        try {
            if (isPacketGroupMember(packet, userId)) {
                return true;
            }
        } catch (ResponseStatusException e) {
            return true;
        } catch (RuntimeException e) {
            return true;
        }
        Instant created = packet.getCreatedAt();
        return created != null && created.isAfter(Instant.now().minusSeconds(60));
    }

    private boolean isLocalGroupMember(String groupId, String userId) {
        if (groupId == null || groupId.isBlank()) {
            return false;
        }
        try {
            return groupAccessService.isLocalMember(groupId, userId);
        } catch (ResponseStatusException e) {
            if ("GROUP_NOT_FOUND".equals(e.getReason())) {
                return false;
            }
            throw e;
        }
    }

    private boolean isGroupMember(String groupId, String userId) {
        if (groupId == null || groupId.isBlank()) {
            return false;
        }
        // 腾讯 get_role_in_group 要 IM 号；入参 userId 为业务号
        String role = imAdminClient.getRoleInGroup(groupId, imUserIdService.toIm(userId));
        return role != null && !"NotMember".equals(role);
    }

    public RedPacketClaimUiState resolveClaimUiState(WalletRedPacket packet, String userId) {
        if (claimRepository.existsByPacketIdAndUserId(packet.getId(), userId)) {
            return RedPacketClaimUiState.RECEIVED;
        }
        if (redisClaim(packet.getId(), userId) != null) {
            return RedPacketClaimUiState.RECEIVED;
        }
        if (packet.getStatus() == RedPacketStatus.ACTIVE && packet.getRemainingCount() > 0) {
            if (packet.getPacketType() == RedPacketType.NORMAL_GROUP
                || packet.getPacketType() == RedPacketType.LUCKY_GROUP) {
                return RedPacketClaimUiState.CAN_OPEN;
            }
        }
        return RedPacketClaimUiState.EMPTY;
    }

    public Long myClaimAmount(WalletRedPacket packet, String userId) {
        Long stored = claimRepository.findByPacketIdAndUserId(packet.getId(), userId)
            .map(WalletRedPacketClaim::getAmount)
            .orElse(null);
        if (stored != null) {
            return stored;
        }
        RedPacketGrabStore.PendingClaim pending = redisClaim(packet.getId(), userId);
        return pending == null ? null : pending.amount();
    }

    private RedPacketGrabStore.PendingClaim redisClaim(long packetId, String userId) {
        if (grabStore == null || userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return grabStore.claimOf(packetId, userId);
        } catch (RuntimeException e) {
            log.warn("red packet redis claim read failed packetId={}: {}", packetId, e.getMessage());
            return null;
        }
    }

    private static long parseClaimId(String claimId) {
        try {
            return Long.parseLong(claimId);
        } catch (RuntimeException e) {
            return ClaimIds.next();
        }
    }

    private void debitSend(String senderUserId, WalletCurrency currency, long charge, long fee,
                           long packetId, String exclusiveUserId, String greeting) {
        ledgerService.debit(senderUserId, currency, charge, WalletLedgerType.RED_PACKET_SEND,
            "RED_PACKET", packetId, exclusiveUserId, greeting);
        if (fee > 0) {
            ledgerService.debit(senderUserId, currency, fee, WalletLedgerType.FEE, "RED_PACKET", packetId, null, null);
            recordFee(currency, fee);
        }
    }

    private void recordFee(WalletCurrency currency, long fee) {
        WalletPlatformStats stats = statsRepository.findById(1L).orElseGet(() -> {
            WalletPlatformStats s = new WalletPlatformStats();
            s.setId(1L);
            return statsRepository.save(s);
        });
        if (currency == WalletCurrency.USDT) {
            stats.setTotalFeeUsdtMicro(stats.getTotalFeeUsdtMicro() + fee);
        } else {
            stats.setTotalFeePlatformFen(stats.getTotalFeePlatformFen() + fee);
        }
        statsRepository.save(stats);
    }
}
