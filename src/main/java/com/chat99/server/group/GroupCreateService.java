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
    private final PayPinService payPinService;
    private final WalletLedgerService walletLedgerService;
    private final TransactionTemplate transactionTemplate;

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
            CommunityCreatePayment previous = paymentRepository.findById(requestedGroupId).orElse(null);
            if (previous != null) {
                if (!creatorUserId.equals(previous.getOwnerUserId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "GROUP_CREATE_REQUEST_CONFLICT");
                }
                return profileService.getDetailFresh(requestedGroupId, creatorUserId);
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
        if (requestedGroupId != null) {
            CommunityCreatePayment previous = paymentRepository.findById(requestedGroupId).orElse(null);
            if (previous != null) {
                if (!creatorUserId.equals(previous.getOwnerUserId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "GROUP_CREATE_REQUEST_CONFLICT");
                }
                return requestedGroupId;
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

        String avatarUrl = avatarDefaults.resolve(req.avatarUrl());
        WalletCurrency priceCurrency = null;
        long priceMinor = 0;
        if (requestedGroupId != null) {
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
        if (requestedGroupId != null) {
            CommunityCreatePayment payment = new CommunityCreatePayment();
            payment.setGroupId(groupId);
            payment.setOwnerUserId(creatorUserId);
            payment.setCurrency(priceCurrency);
            payment.setAmountMinor(priceMinor);
            paymentRepository.saveAndFlush(payment);
        }

        return groupId;
    }

    static String paidGroupId(String clientRequestId) {
        if (clientRequestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GROUP_CREATE_REQUEST_ID_REQUIRED");
        }
        try {
            UUID id = UUID.fromString(clientRequestId.trim());
            if (!id.toString().equalsIgnoreCase(clientRequestId.trim())) {
                throw new IllegalArgumentException("noncanonical UUID");
            }
            return "@TGS#_P" + id.toString().replace("-", "");
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
