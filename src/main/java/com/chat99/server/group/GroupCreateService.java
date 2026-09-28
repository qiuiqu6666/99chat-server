package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.PayPinService;
import com.chat99.server.wallet.WalletCurrency;
import com.chat99.server.wallet.WalletLedgerService;
import com.chat99.server.wallet.WalletLedgerType;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupCreateService {

    public record JoinOptionsBody(GroupJoinOption applyJoinOption, GroupJoinOption inviteJoinOption) {}

    public record CreateGroupRequest(
        String groupType,
        String groupName,
        String avatarUrl,
        List<String> memberUserIds,
        JoinOptionsBody joinOptions,
        String introduction,
        String clientRequestId,
        String payPin,
        String expectedPriceCurrency,
        Long expectedPriceMinor,
        boolean channel) {}

    private final ImAdminClient im;
    private final ImUserIdService imUserIdService;
    private final UserFriendService friendService;
    private final UserRepository userRepository;
    private final UserOwnedGroupService ownedGroupService;
    private final GroupJoinLimitService joinLimitService;
    private final GroupProjectionTxService projectionTx;
    private final GroupSettingsRepository settingsRepository;
    private final GroupProfileService profileService;
    private final GroupAvatarDefaults avatarDefaults;
    private final GroupCreateLimitConfigService createConfig;
    private final CommunityCreatePaymentRepository paymentRepository;
    private final GroupProfileRepository profileRepository;
    private final PayPinService payPinService;
    private final WalletLedgerService walletLedgerService;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom rng = new SecureRandom();

    private static final char[] GROUP_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final char[] GROUP_ID_LETTERS = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] GROUP_ID_DIGITS = "0123456789".toCharArray();
    static final int GROUP_ID_LENGTH = 10;

    public GroupCreateService(ImAdminClient im,
                              ImUserIdService imUserIdService,
                              UserFriendService friendService,
                              UserRepository userRepository,
                              UserOwnedGroupService ownedGroupService,
                              GroupJoinLimitService joinLimitService,
                              GroupProjectionTxService projectionTx,
                              GroupSettingsRepository settingsRepository,
                              GroupProfileService profileService,
                              GroupAvatarDefaults avatarDefaults,
                              GroupCreateLimitConfigService createConfig,
                              CommunityCreatePaymentRepository paymentRepository,
                              GroupProfileRepository profileRepository,
                              PayPinService payPinService,
                              WalletLedgerService walletLedgerService,
                              PlatformTransactionManager transactionManager) {
        this.im = im;
        this.imUserIdService = imUserIdService;
        this.friendService = friendService;
        this.userRepository = userRepository;
        this.ownedGroupService = ownedGroupService;
        this.joinLimitService = joinLimitService;
        this.projectionTx = projectionTx;
        this.settingsRepository = settingsRepository;
        this.profileService = profileService;
        this.avatarDefaults = avatarDefaults;
        this.createConfig = createConfig;
        this.paymentRepository = paymentRepository;
        this.profileRepository = profileRepository;
        this.payPinService = payPinService;
        this.walletLedgerService = walletLedgerService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public GroupProfileView createGroup(String creatorUserId, CreateGroupRequest req) {
        validateCreateRequest(req);
        boolean community = GroupCreateLimitConfigService.isCommunity(req.groupType());
        if (req.channel() && !community) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CHANNEL_REQUIRES_COMMUNITY");
        }
        boolean paidCommunity = community && !req.channel();
        String requestedGroupId = paidCommunity ? paidGroupId(req.clientRequestId()) : null;
        if (paidCommunity) {
            if (req.payPin() == null || req.payPin().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PAY_PIN_REQUIRED");
            }
            CommunityCreatePayment previous = findExistingPayment(req.clientRequestId());
            if (previous != null) {
                if (!creatorUserId.equals(previous.getOwnerUserId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "GROUP_CREATE_REQUEST_CONFLICT");
                }
                return profileService.getDetailFresh(previous.getGroupId(), creatorUserId);
            }
            payPinService.requireSetAndVerify(creatorUserId, req.payPin());
        }
        AtomicReference<String> createdInIm = new AtomicReference<>();
        String groupId;
        try {
            groupId = transactionTemplate.execute(status ->
                createGroupPersist(creatorUserId, req, requestedGroupId, createdInIm));
        } catch (RuntimeException failure) {
            String newImGroupId = createdInIm.get();
            if (newImGroupId != null) {
                try {
                    im.destroyGroup(newImGroupId);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        return profileService.getDetailFresh(groupId, creatorUserId);
    }

    /** IM 建群 + 本地投影/设置写入与扣费。响应组装在事务提交后读取。 */
    private String createGroupPersist(String creatorUserId, CreateGroupRequest req,
                                      String requestedGroupId, AtomicReference<String> createdInIm) {
        boolean paidCommunity = GroupCreateLimitConfigService.isCommunity(req.groupType()) && !req.channel();
        if (paidCommunity) {
            CommunityCreatePayment previous = findExistingPayment(req.clientRequestId());
            if (previous != null) {
                if (!creatorUserId.equals(previous.getOwnerUserId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "GROUP_CREATE_REQUEST_CONFLICT");
                }
                return previous.getGroupId();
            }
        }
        String groupType = req.groupType().trim();
        if (!GroupAccessService.BACKEND_CREATE_GROUP_TYPES.contains(groupType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GROUP_TYPE_NOT_SUPPORTED");
        }
        List<String> memberUserIds = normalizeMemberUserIds(req.memberUserIds(), creatorUserId);
        validateInitialMembers(creatorUserId, memberUserIds);
        joinLimitService.assertCanCreateGroup(creatorUserId, groupType, memberUserIds);

        GroupJoinOption apply = req.channel() ? GroupJoinOption.free_access
            : req.joinOptions() == null || req.joinOptions().applyJoinOption() == null
                ? GroupJoinOption.need_permission
                : req.joinOptions().applyJoinOption();
        GroupJoinOption invite = req.joinOptions() == null || req.joinOptions().inviteJoinOption() == null
            ? GroupJoinOption.need_permission
            : req.joinOptions().inviteJoinOption();

        if (requestedGroupId == null) {
            requestedGroupId = allocateGroupId(groupType);
        }
        String avatarUrl = avatarDefaults.resolve(req.avatarUrl());
        WalletCurrency priceCurrency = null;
        long priceMinor = 0;
        if (paidCommunity) {
            priceCurrency = createConfig.getCommunityPriceCurrency();
            priceMinor = createConfig.getCommunityPriceMinor();
            if (!priceCurrency.getApiCode().equals(req.expectedPriceCurrency())
                || req.expectedPriceMinor() == null || priceMinor != req.expectedPriceMinor()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "COMMUNITY_PRICE_CHANGED");
            }
            walletLedgerService.debit(creatorUserId, priceCurrency, priceMinor,
                WalletLedgerType.GROUP_CREATE, "COMMUNITY_CREATE", null, null,
                "Community creation: " + requestedGroupId);
        }
        String groupId;
        try {
            List<String> imMemberIds = memberUserIds.stream().map(imUserIdService::toIm).toList();
            groupId = im.createGroup(
                imUserIdService.toIm(creatorUserId),
                groupType,
                req.groupName().trim(),
                avatarUrl,
                req.introduction(),
                imMemberIds,
                apply,
                invite,
                requestedGroupId);
            createdInIm.set(groupId);
            if (requestedGroupId != null && !requestedGroupId.equals(groupId)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "IM_GROUP_ID_MISMATCH");
            }
        } catch (ImRestException e) {
            String ownerIm = imUserIdService.toIm(creatorUserId);
            if (requestedGroupId == null || im.getGroupBaseInfo(requestedGroupId)
                .filter(info -> ownerIm.equals(info.ownerAccount())).isEmpty()) {
                throw mapImError(e);
            }
            groupId = requestedGroupId;
        }

        projectionTx.seedGroupAfterCreate(
            groupId,
            creatorUserId,
            groupType,
            req.groupName().trim(),
            avatarUrl,
            req.introduction(),
            memberUserIds);
        if (req.channel()) {
            projectionTx.markChannel(groupId);
        }
        saveJoinOptions(groupId, apply, invite);
        ownedGroupService.recordCreated(creatorUserId, groupType, groupId);
        if (paidCommunity) {
            CommunityCreatePayment payment = new CommunityCreatePayment();
            payment.setGroupId(groupId);
            payment.setOwnerUserId(creatorUserId);
            payment.setCurrency(priceCurrency);
            payment.setAmountMinor(priceMinor);
            paymentRepository.saveAndFlush(payment);
        }

        return groupId;
    }

    /**
     * 收费社群群号：{@code @TGS#_} 加 10 位英文和数字，且不能是纯数字。
     * 同一个客户端 UUID 始终得到同一个号。界面别名是这 10 位前面加 {@code @}。
     */
    static String paidGroupId(String clientRequestId) {
        return "@TGS#_" + mixedToken(requireClientRequestUuid(clientRequestId));
    }

    /** 短暂上线过的 10 位纯数字群号。旧请求重试时用来找回已扣费的群。 */
    static String digitPaidGroupId(String clientRequestId) {
        UUID id = requireClientRequestUuid(clientRequestId);
        long mixed = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17);
        long suffix = 1_000_000_000L + Math.floorMod(mixed & Long.MAX_VALUE, 9_000_000_000L);
        return "@TGS#_" + suffix;
    }

    /** 改成 10 位尾数之前的群号。旧请求重试时用它找回已扣费的群。 */
    static String legacyPaidGroupId(String clientRequestId) {
        UUID id = requireClientRequestUuid(clientRequestId);
        return "@TGS#_P" + id.toString().replace("-", "");
    }

    private CommunityCreatePayment findExistingPayment(String clientRequestId) {
        return paymentRepository.findById(paidGroupId(clientRequestId))
            .or(() -> paymentRepository.findById(digitPaidGroupId(clientRequestId)))
            .or(() -> paymentRepository.findById(legacyPaidGroupId(clientRequestId)))
            .orElse(null);
    }

    private String allocateGroupId(String groupType) {
        boolean community = GroupCreateLimitConfigService.isCommunity(groupType);
        for (int i = 0; i < 8; i++) {
            String token = randomMixedToken(rng);
            String groupId = community ? "@TGS#_" + token : token;
            if (!profileRepository.existsById(groupId) && !paymentRepository.existsById(groupId)) {
                return groupId;
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GROUP_ID_EXHAUSTED");
    }

    static String mixedToken(UUID id) {
        byte[] raw = new byte[16];
        ByteBuffer.wrap(raw).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return tokenFromHash(hash);
    }

    static String randomMixedToken(SecureRandom random) {
        byte[] hash = new byte[16];
        random.nextBytes(hash);
        return tokenFromHash(hash);
    }

    private static String tokenFromHash(byte[] hash) {
        char[] out = new char[GROUP_ID_LENGTH];
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < GROUP_ID_LENGTH; i++) {
            char c = GROUP_ID_ALPHABET[(hash[i] & 0xff) % GROUP_ID_ALPHABET.length];
            out[i] = c;
            if (c >= 'a') {
                hasLetter = true;
            } else {
                hasDigit = true;
            }
        }
        if (!hasLetter) {
            out[0] = GROUP_ID_LETTERS[(hash[10] & 0xff) % GROUP_ID_LETTERS.length];
        }
        if (!hasDigit) {
            out[GROUP_ID_LENGTH - 1] = GROUP_ID_DIGITS[(hash[11] & 0xff) % GROUP_ID_DIGITS.length];
        }
        return new String(out);
    }

    static boolean isMixedGroupToken(String token) {
        if (token == null || token.length() != GROUP_ID_LENGTH) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= 'a' && c <= 'z') {
                hasLetter = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else {
                return false;
            }
        }
        return hasLetter && hasDigit;
    }

    private static UUID requireClientRequestUuid(String clientRequestId) {
        if (clientRequestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GROUP_CREATE_REQUEST_ID_REQUIRED");
        }
        try {
            UUID id = UUID.fromString(clientRequestId.trim());
            if (!id.toString().equalsIgnoreCase(clientRequestId.trim())) {
                throw new IllegalArgumentException("noncanonical UUID");
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GROUP_CREATE_REQUEST_ID_INVALID");
        }
    }

    private void validateCreateRequest(CreateGroupRequest req) {
        if (req == null
            || req.groupType() == null || req.groupType().isBlank()
            || req.groupName() == null || req.groupName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (req.memberUserIds() != null && req.memberUserIds().size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE");
        }
    }

    private void validateInitialMembers(String creatorUserId, List<String> memberUserIds) {
        for (String peerId : memberUserIds) {
            requireUserExists(peerId);
            if (!friendService.isMutualActive(creatorUserId, peerId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_FRIEND");
            }
        }
    }

    private void saveJoinOptions(String groupId, GroupJoinOption apply, GroupJoinOption invite) {
        GroupSettings settings = settingsRepository.findById(groupId).orElseGet(() -> {
            GroupSettings created = new GroupSettings();
            created.setGroupId(groupId);
            return created;
        });
        settings.setApplyJoinOption(apply);
        settings.setInviteJoinOption(invite);
        settingsRepository.save(settings);
    }

    private void requireActiveUser(String userId) {
        userRepository.findByUserId(userId)
            .filter(u -> u.getStatus() == 1)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private void requireUserExists(String userId) {
        userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private static List<String> normalizeMemberUserIds(List<String> memberUserIds, String creatorUserId) {
        if (memberUserIds == null || memberUserIds.isEmpty()) {
            return List.of();
        }
        return memberUserIds.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty() && !s.equals(creatorUserId))
            .distinct()
            .toList();
    }

    private static ResponseStatusException mapImError(ImRestException e) {
        if ("IM_NOT_CONFIGURED".equals(e.getMessage())) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IM_NOT_CONFIGURED");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "IM_REST_ERROR");
    }
}
