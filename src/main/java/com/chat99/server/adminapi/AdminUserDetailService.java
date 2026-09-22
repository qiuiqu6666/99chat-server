package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.chat99.server.common.PhoneUtils;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.notify.PlatformWalletNoticeProperties;
import com.chat99.server.notify.SystemNotifyProperties;
import com.chat99.server.sync.PhotoSyncService;
import com.chat99.server.sync.SyncProperties;
import com.chat99.server.user.DeviceModelDisplayService;
import com.chat99.server.user.LoginLog;
import com.chat99.server.user.LoginLogRepository;
import com.chat99.server.user.User;
import com.chat99.server.user.UserDevice;
import com.chat99.server.user.UserDeviceRepository;
import com.chat99.server.user.UserFriend;
import com.chat99.server.user.UserFriendRepository;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.UserWallet;
import com.chat99.server.wallet.UserWalletRepository;
import com.chat99.server.wallet.WalletWithdrawalRepository;
import com.chat99.server.wallet.WithdrawalStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserDetailService {

    static final int DETAIL_FRIENDS_LIMIT = 150;
    static final int DETAIL_GROUPS_LIMIT = 80;
    static final int DETAIL_DEVICES_LIMIT = 100;

    private final AdminUserManagementService users;
    private final UserRepository userRepository;
    private final LoginLogRepository loginLogRepository;
    private final UserDeviceRepository deviceRepository;
    private final UserWalletRepository walletRepository;
    private final WalletWithdrawalRepository withdrawalRepository;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;
    private final UserFriendRepository userFriendRepository;
    private final PhotoSyncService photoSyncService;
    private final SyncProperties syncProps;
    private final PhoneUtils phoneUtils;
    private final AdminAuditService auditService;
    private final SystemNotifyProperties systemNotifyProps;
    private final PlatformWalletNoticeProperties walletNoticeProps;
    private final DeviceModelDisplayService modelDisplay;

    public AdminUserDetailService(AdminUserManagementService users,
                                  UserRepository userRepository,
                                  LoginLogRepository loginLogRepository,
                                  UserDeviceRepository deviceRepository,
                                  UserWalletRepository walletRepository,
                                  WalletWithdrawalRepository withdrawalRepository,
                                  ImAdminClient imAdmin,
                                  ImUserIdService imUserIdService,
                                  UserFriendRepository userFriendRepository,
                                  PhotoSyncService photoSyncService,
                                  SyncProperties syncProps,
                                  PhoneUtils phoneUtils,
                                  AdminAuditService auditService,
                                  SystemNotifyProperties systemNotifyProps,
                                  PlatformWalletNoticeProperties walletNoticeProps,
                                  DeviceModelDisplayService modelDisplay) {
        this.users = users;
        this.userRepository = userRepository;
        this.loginLogRepository = loginLogRepository;
        this.deviceRepository = deviceRepository;
        this.walletRepository = walletRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
        this.userFriendRepository = userFriendRepository;
        this.photoSyncService = photoSyncService;
        this.syncProps = syncProps;
        this.phoneUtils = phoneUtils;
        this.auditService = auditService;
        this.systemNotifyProps = systemNotifyProps;
        this.walletNoticeProps = walletNoticeProps;
        this.modelDisplay = modelDisplay;
    }

    public UserDetailResponse getDetail(String userUid) {
        User user = users.requireUser(userUid);
        String imAccount = imUserIdService.toIm(user.getUserId());
        String imAvatar = imAdmin.getPortraitImageUrls(List.of(imAccount)).get(imAccount);
        AdminUserManagementService.UserListItem profile =
            users.toListItemPublic(user, imAvatar);
        int friendTotal = (int) userFriendRepository.countByUserIdAndStatus(user.getUserId(), UserFriend.STATUS_ACTIVE);
        int groupTotal = imAdmin.joinedGroupTotal(imAccount);
        List<FriendItem> friends = loadFriends(user.getUserId(), DETAIL_FRIENDS_LIMIT);
        List<GroupItem> groups = loadGroups(user.getUserId(), 0, DETAIL_GROUPS_LIMIT);
        List<DeviceItem> devices = loadDevices(user.getUserId(), DETAIL_DEVICES_LIMIT);
        SameIpBlock sameIp = loadSameIp(user.getUserId(), 50);
        return new UserDetailResponse(
            profile,
            sliceBlock(friends, friendTotal, DETAIL_FRIENDS_LIMIT),
            sliceBlock(groups, groupTotal, DETAIL_GROUPS_LIMIT),
            sliceBlock(devices, devices.size(), DETAIL_DEVICES_LIMIT),
            new SameIpAccounts(sameIp));
    }

    public PagedFriendsResponse listFriends(String userUid, int page, int pageSize) {
        users.requireUser(userUid);
        List<UserFriend> all = userFriendRepository.findByUserIdAndStatus(userUid, UserFriend.STATUS_ACTIVE).stream()
            .sorted(Comparator.comparing(this::friendSortTime).reversed())
            .toList();
        int total = all.size();
        int safeSize = clampPageSize(pageSize);
        int safePage = Math.max(page, 1);
        int from = (safePage - 1) * safeSize;
        int to = Math.min(from + safeSize, all.size());
        List<FriendItem> items = all.subList(Math.min(from, all.size()), to).stream()
            .map(this::toFriendItemFromRow)
            .toList();
        return new PagedFriendsResponse(items, total, safePage, safeSize, total > to);
    }

    public PagedGroupsResponse listGroups(String userUid, int page, int pageSize) {
        users.requireUser(userUid);
        String imAccount = imUserIdService.toIm(userUid);
        int total = imAdmin.joinedGroupTotal(imAccount);
        int safeSize = clampPageSize(pageSize);
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * safeSize;
        List<GroupItem> items = imAdmin.listJoinedGroups(imAccount, offset, safeSize).stream()
            .map(this::toGroupItem)
            .toList();
        return new PagedGroupsResponse(items, total, safePage, safeSize, offset + items.size() < total);
    }

    public PagedDevicesResponse listLoginLogs(String userUid, int page, int pageSize) {
        users.requireUser(userUid);
        int safeSize = clampPageSize(pageSize);
        int safePage = Math.max(page, 1);
        var rows = loginLogRepository.findByUserIdOrderByCreatedAtDesc(
            userUid, PageRequest.of(safePage - 1, safeSize));
        List<DeviceItem> items = rows.stream().map(log -> logToDeviceItem(userUid, log)).toList();
        long total = loginLogRepository.countByUserId(userUid);
        return new PagedDevicesResponse(items, total, safePage, safeSize, (long) safePage * safeSize < total);
    }

    public PagedRelatedResponse listRelatedByIp(String userUid, int page, int pageSize) {
        PagedRelatedWithHintResponse withHint = listRelatedByIp(userUid, null, page, pageSize);
        return new PagedRelatedResponse(
            withHint.items(), withHint.total(), withHint.page(), withHint.pageSize(), withHint.truncated());
    }

    public PagedRelatedWithHintResponse listRelatedByIp(
        String userUid, String ipFilter, int page, int pageSize) {
        User user = users.requireUser(userUid);
        SameIpBlock block = loadSameIp(user.getUserId(), 500);
        List<RelatedUserItem> items = new ArrayList<>(block.items());
        if (ipFilter != null && !ipFilter.isBlank()) {
            String ip = ipFilter.trim();
            items = items.stream().filter(i -> ip.equals(i.sharedIp())).toList();
        }
        int total = (ipFilter != null && !ipFilter.isBlank()) ? items.size() : block.total();
        PagedRelatedResponse paged = paginateRelated(items, total, page, pageSize);
        return new PagedRelatedWithHintResponse(
            paged.items(), paged.total(), paged.page(), paged.pageSize(), paged.truncated(), block.hint());
    }

    public PagedRelatedResponse listRelatedByDevice(String userUid, String deviceId, int page, int pageSize) {
        PagedRelatedWithHintResponse withHint = listRelatedByDeviceFlexible(userUid, deviceId, page, pageSize);
        return new PagedRelatedResponse(
            withHint.items(), withHint.total(), withHint.page(), withHint.pageSize(), withHint.truncated());
    }

    public PagedRelatedWithHintResponse listRelatedByDeviceFlexible(
        String userUid, String deviceId, int page, int pageSize) {
        boolean hasUid = userUid != null && !userUid.isBlank();
        boolean hasDevice = deviceId != null && !deviceId.isBlank();
        if (!hasUid && !hasDevice) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "user_uid or device_id required");
        }
        int safeSize = clampPageSize(pageSize);
        int safePage = Math.max(page, 1);

        if (hasDevice && !hasUid) {
            String dev = deviceId.trim();
            long total = loginLogRepository.countDistinctUserIdsByDeviceId(dev);
            List<String> userIds = loginLogRepository.findDistinctUserIdsByDeviceId(
                dev, PageRequest.of(safePage - 1, safeSize));
            List<RelatedUserItem> items = buildRelatedItemsForDevice(userIds, dev);
            return new PagedRelatedWithHintResponse(
                items,
                (int) total,
                safePage,
                safeSize,
                (long) safePage * safeSize < total,
                items.isEmpty() && total == 0 ? "no_users_on_device" : null);
        }

        User user = users.requireUser(userUid.trim());
        if (!hasDevice) {
            SameDeviceBlock block = loadSameDevice(user.getUserId(), 500);
            PagedRelatedResponse paged = paginateRelated(block.items(), block.total(), page, pageSize);
            return new PagedRelatedWithHintResponse(
                paged.items(), paged.total(), paged.page(), paged.pageSize(), paged.truncated(), block.hint());
        }

        String dev = deviceId.trim();
        List<String> relatedIds = loginLogRepository.findDistinctUserIdsByDeviceExcluding(
            dev, user.getUserId(), PageRequest.of(0, 500));
        List<RelatedUserItem> items = buildRelatedItemsForDevice(relatedIds, dev);
        PagedRelatedResponse paged = paginateRelated(items, items.size(), page, pageSize);
        return new PagedRelatedWithHintResponse(
            paged.items(), paged.total(), paged.page(), paged.pageSize(), paged.truncated(), null);
    }

    @Transactional
    public OkUnfreezeResult loginUnfreeze(HttpServletRequest http, String adminUsername,
                                          String userUid, String loginKey) {
        User user = resolveUserForUnfreeze(userUid, loginKey);
        UserWallet w = walletRepository.findById(user.getUserId()).orElse(null);
        boolean payPinCleared = false;
        if (w != null) {
            w.setPayPinFailCount(0);
            w.setPayPinLockedUntil(null);
            walletRepository.save(w);
            payPinCleared = true;
        }
        auditService.log(http, adminUsername, "user.login_unfreeze", user.getUserId(),
            Map.of("login_key", loginKey != null ? loginKey : user.getUserId(),
                "pay_pin_cleared", payPinCleared));
        return new OkUnfreezeResult(true, user.getUserId(), payPinCleared);
    }

    public PhoneAlbumConfigResponse phoneAlbumConfig(String userUid) {
        users.requireUser(userUid);
        return new PhoneAlbumConfigResponse(
            syncProps.photoPrefix(),
            syncProps.presignExpireSeconds(),
            syncProps.uploadExpireMinutes(),
            true);
    }

    public PhoneAlbumListResponse phoneAlbumList(String userUid, int page, int pageSize) {
        users.requireUser(userUid);
        int safePage = Math.max(page, 1) - 1;
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        PhotoSyncService.PhotoListResponse list = photoSyncService.listPhotos(userUid, safePage, safeSize);
        List<PhotoAlbumItem> items = list.items().stream()
            .map(p -> new PhotoAlbumItem(
                p.photoUuid(),
                p.localAssetId(),
                p.contentHash(),
                p.originUrl(),
                p.thumbUrl(),
                p.previewUrl(),
                p.takenAt(),
                p.width(),
                p.height(),
                p.sizeBytes()))
            .toList();
        return new PhoneAlbumListResponse(items, safePage + 1, safeSize, list.hasMore());
    }

    private User resolveUserForUnfreeze(String userUid, String loginKey) {
        if (userUid != null && !userUid.isBlank()) {
            return users.requireUser(userUid);
        }
        if (loginKey == null || loginKey.isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "user_uid or login_key required");
        }
        String key = loginKey.trim();
        Optional<User> byUid = userRepository.findByUserId(key);
        if (byUid.isPresent()) {
            return byUid.get();
        }
        try {
            if (key.startsWith("+")) {
                return userRepository.findByPhone(phoneUtils.parseE164(key).e164())
                    .orElseThrow(notFound());
            }
            if (key.matches("^[0-9]+$")) {
                PhoneUtils.Parsed p = phoneUtils.parseWithRegion(key, "CN");
                return userRepository.findByPhone(p.e164()).orElseThrow(notFound());
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        return userRepository.findByPhone("+" + key)
            .or(() -> userRepository.findByPhone(key))
            .orElseThrow(notFound());
    }

    private static java.util.function.Supplier<AdminApiException> notFound() {
        return () -> new AdminApiException(HttpStatus.NOT_FOUND, "user_not_found", "user_not_found");
    }

    private List<FriendItem> loadFriends(String userId, int limit) {
        return userFriendRepository.findByUserIdAndStatus(userId, UserFriend.STATUS_ACTIVE).stream()
            .sorted(Comparator.comparing(this::friendSortTime).reversed())
            .limit(limit)
            .map(this::toFriendItemFromRow)
            .toList();
    }

    private Instant friendSortTime(UserFriend row) {
        if (row.getAddedAt() != null) {
            return row.getAddedAt();
        }
        if (row.getImAddTime() != null) {
            return row.getImAddTime();
        }
        return row.getCreatedAt();
    }

    private FriendItem toFriendItemFromRow(UserFriend row) {
        String nick = row.getFriendNickname();
        if (nick == null || nick.isBlank()) {
            nick = userRepository.findByUserId(row.getFriendUserId()).map(User::getNickname).orElse(null);
        }
        if (nick == null || nick.isBlank()) {
            nick = knownSystemNickname(row.getFriendUserId());
        }
        if (nick == null || nick.isBlank()) {
            nick = row.getFriendUserId();
        }
        Instant added = row.getAddedAt() != null ? row.getAddedAt() : row.getImAddTime();
        Long addTimeSec = added != null ? added.getEpochSecond() : null;
        return new FriendItem(row.getFriendUserId(), nick, addTimeSec, row.getFriendAvatarUrl());
    }

    private List<GroupItem> loadGroups(String userId, int offset, int limit) {
        String imAccount = imUserIdService.toIm(userId);
        return imAdmin.listJoinedGroups(imAccount, offset, limit).stream().map(this::toGroupItem).toList();
    }

    private List<DeviceItem> loadDevices(String userId, int limit) {
        Map<String, DeviceItem> merged = new LinkedHashMap<>();
        for (UserDevice d : deviceRepository.findByUserIdOrderByLastLoginAtDesc(userId)) {
            merged.put(d.getDeviceId(), deviceFromUserDevice(d));
        }
        for (LoginLog log : loginLogRepository.findByUserIdOrderByCreatedAtDesc(
            userId, PageRequest.of(0, limit * 3))) {
            if (log.getDeviceId() == null || log.getDeviceId().isBlank()) {
                continue;
            }
            merged.putIfAbsent(log.getDeviceId(), logToDeviceItem(userId, log));
        }
        return merged.values().stream().limit(limit).toList();
    }

    private SameIpBlock loadSameIp(String userId, int itemLimit) {
        List<String> ips = loginLogRepository.findDistinctSuccessIpsByUserId(userId);
        if (ips.isEmpty()) {
            return new SameIpBlock(List.of(), 0, List.of(), "no_login_ip_in_history");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<RelatedUserItem> items = new ArrayList<>();
        for (String ip : ips) {
            for (String otherUid : loginLogRepository.findDistinctUserIdsByIpExcluding(
                ip, userId, PageRequest.of(0, itemLimit))) {
                if (!seen.add(otherUid)) {
                    continue;
                }
                userRepository.findByUserId(otherUid).ifPresent(u -> items.add(toRelatedUser(u, ip)));
                if (items.size() >= itemLimit) {
                    return new SameIpBlock(ips, ips.size(), items, null);
                }
            }
        }
        return new SameIpBlock(ips, ips.size(), items, null);
    }

    private SameDeviceBlock loadSameDevice(String userId, int itemLimit) {
        LinkedHashSet<String> deviceIds = new LinkedHashSet<>();
        deviceIds.addAll(loginLogRepository.findDistinctDeviceIdsByUserId(userId));
        for (UserDevice d : deviceRepository.findByUserIdOrderByLastLoginAtDesc(userId)) {
            if (d.getDeviceId() != null && !d.getDeviceId().isBlank()) {
                deviceIds.add(d.getDeviceId());
            }
        }
        if (deviceIds.isEmpty()) {
            return new SameDeviceBlock(List.of(), 0, List.of(), "no_device_in_history");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<RelatedUserItem> items = new ArrayList<>();
        for (String deviceId : deviceIds) {
            for (String otherUid : loginLogRepository.findDistinctUserIdsByDeviceExcluding(
                deviceId, userId, PageRequest.of(0, itemLimit))) {
                if (!seen.add(otherUid)) {
                    continue;
                }
                userRepository.findByUserId(otherUid)
                    .ifPresent(u -> items.add(toRelatedUser(u, null, deviceId)));
                if (items.size() >= itemLimit) {
                    return new SameDeviceBlock(new ArrayList<>(deviceIds), items.size(), items, null);
                }
            }
        }
        return new SameDeviceBlock(new ArrayList<>(deviceIds), items.size(), items, null);
    }

    private List<RelatedUserItem> buildRelatedItems(List<String> userIds) {
        List<RelatedUserItem> items = new ArrayList<>();
        for (String uid : userIds) {
            userRepository.findByUserId(uid).ifPresent(u -> items.add(toRelatedUser(u, null)));
        }
        return items;
    }

    private List<RelatedUserItem> buildRelatedItemsForDevice(List<String> userIds, String deviceId) {
        List<RelatedUserItem> items = new ArrayList<>();
        for (String uid : userIds) {
            userRepository.findByUserId(uid)
                .ifPresent(u -> items.add(toRelatedUser(u, null, deviceId)));
        }
        return items;
    }

    private PagedRelatedResponse paginateRelated(List<RelatedUserItem> all, int total, int page, int pageSize) {
        int safeSize = clampPageSize(pageSize);
        int safePage = Math.max(page, 1);
        int from = (safePage - 1) * safeSize;
        int to = Math.min(from + safeSize, all.size());
        List<RelatedUserItem> slice = all.subList(Math.min(from, all.size()), to);
        return new PagedRelatedResponse(slice, total, safePage, safeSize, to < all.size());
    }

    private RelatedUserItem toRelatedUser(User u, String sharedIp) {
        return toRelatedUser(u, sharedIp, null);
    }

    private RelatedUserItem toRelatedUser(User u, String sharedIp, String sharedDeviceId) {
        String imAccount = imUserIdService.toIm(u.getUserId());
        String avatar = imAdmin.getPortraitImageUrls(List.of(imAccount)).get(imAccount);
        return new RelatedUserItem(
            u.getUserId(),
            u.getNickname(),
            stripPlus(u.getPhone()),
            u.getStatus(),
            AdminUserFormats.resolveListAvatar(avatar, u.getAvatarUrl()),
            sharedIp,
            sharedDeviceId);
    }

    private FriendItem toFriendItem(ImAdminClient.FriendEntry f) {
        String nick = f.nickname();
        if (nick == null || nick.isBlank()) {
            nick = userRepository.findByUserId(f.friendUid()).map(User::getNickname).orElse(null);
        }
        if (nick == null || nick.isBlank()) {
            nick = knownSystemNickname(f.friendUid());
        }
        if (nick == null || nick.isBlank()) {
            nick = f.friendUid();
        }
        return new FriendItem(f.friendUid(), nick, f.addTimeSec(), f.avatarUrl());
    }

    private String knownSystemNickname(String friendUid) {
        if (friendUid == null) {
            return null;
        }
        if (friendUid.equals(systemNotifyProps.senderUserId())) {
            return systemNotifyProps.senderDisplayName();
        }
        if (friendUid.equals(walletNoticeProps.senderUserId())) {
            return walletNoticeProps.senderDisplayName();
        }
        return null;
    }

    private GroupItem toGroupItem(ImAdminClient.JoinedGroupEntry g) {
        return new GroupItem(
            g.groupId(), g.groupName(), g.groupType(), g.joinTimeSec(), g.memberCount(), g.faceUrl());
    }

    private DeviceItem deviceFromUserDevice(UserDevice d) {
        return new DeviceItem(
            d.getDeviceId(),
            AdminUserFormats.mapDeviceType(d.getPlatform()),
            maskToken(d.getDeviceId()),
            d.isTrusted() ? 1 : 0,
            AdminUserFormats.formatTime(d.getLastLoginAt()),
            d.getPlatform(),
            modelDisplay.display(d.getPlatform(), d.getModel()));
    }

    private DeviceItem logToDeviceItem(String userId, LoginLog log) {
        String rawModel = deviceRepository.findByUserIdAndDeviceId(userId, log.getDeviceId())
            .map(UserDevice::getModel)
            .filter(m -> m != null && !m.isBlank())
            .orElse(null);
        String model = modelDisplay.display(log.getClientPlatform(), rawModel);
        return new DeviceItem(
            log.getDeviceId(),
            AdminUserFormats.mapDeviceType(log.getClientPlatform()),
            maskToken(log.getDeviceId()),
            log.isSuccess() ? 1 : 0,
            AdminUserFormats.formatTime(log.getCreatedAt()),
            log.getClientPlatform(),
            model);
    }

    private static String maskToken(String deviceId) {
        if (deviceId == null || deviceId.length() < 8) {
            return deviceId;
        }
        return deviceId.substring(0, 4) + "****" + deviceId.substring(deviceId.length() - 4);
    }

    private static <T> TruncatedBlock<T> sliceBlock(List<T> items, int total, int limit) {
        boolean truncated = total > items.size() || items.size() >= limit;
        return new TruncatedBlock<>(items, total, limit, truncated);
    }

    private static int clampPageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 100);
    }

    private static String stripPlus(String phone) {
        if (phone == null) {
            return null;
        }
        return phone.startsWith("+") ? phone.substring(1) : phone;
    }

    record SameIpBlock(List<String> sharedIps, int total, List<RelatedUserItem> items, String hint) {}

    record SameDeviceBlock(List<String> sharedDeviceIds, int total, List<RelatedUserItem> items, String hint) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UserDetailResponse(
        AdminUserManagementService.UserListItem profile,
        TruncatedBlock<FriendItem> friends,
        TruncatedBlock<GroupItem> groups,
        TruncatedBlock<DeviceItem> devices,
        SameIpAccounts accountsSameIp) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TruncatedBlock<T>(List<T> items, int total, int limit, boolean truncated) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SameIpAccounts(
        List<String> sharedIps,
        int total,
        int limit,
        boolean truncated,
        List<RelatedUserItem> items,
        String hint) {
        SameIpAccounts(SameIpBlock block) {
            this(block.sharedIps(), block.items().size(), 50,
                block.items().size() >= 50, block.items(), block.hint());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FriendItem(String friendUid, String nickname, Long addTime, String friendAvatarFileName) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupItem(
        String groupId, String groupName, String groupType, Long joinTime, Integer memberCount, String faceUrl) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeviceItem(
        String deviceId,
        Integer deviceType,
        String deviceTokenMasked,
        Integer isTrusted,
        String lastLoginTime,
        String platform,
        String model) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RelatedUserItem(
        String userUid,
        String nickname,
        String phoneNum,
        Integer userStatus,
        String userAvatarFileName,
        String sharedIp,
        String sharedDeviceId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PagedFriendsResponse(
        List<FriendItem> items, int total, int page, int pageSize, boolean truncated) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PagedGroupsResponse(
        List<GroupItem> items, int total, int page, int pageSize, boolean truncated) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PagedDevicesResponse(
        List<DeviceItem> items, long total, int page, int pageSize, boolean hasMore) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PagedRelatedResponse(
        List<RelatedUserItem> items, int total, int page, int pageSize, boolean truncated) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PagedRelatedWithHintResponse(
        List<RelatedUserItem> items,
        int total,
        int page,
        int pageSize,
        boolean truncated,
        String hint) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OkUnfreezeResult(boolean ok, String userUid, boolean payPinUnfrozen) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PhoneAlbumConfigResponse(
        String photoPrefix, int presignExpireSeconds, int uploadExpireMinutes, boolean enabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PhotoAlbumItem(
        String photoUuid,
        String localAssetId,
        String contentHash,
        String originUrl,
        String thumbUrl,
        String previewUrl,
        Long takenAt,
        Integer width,
        Integer height,
        Long sizeBytes) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PhoneAlbumListResponse(
        List<PhotoAlbumItem> items, int page, int pageSize, boolean hasMore) {}
}
