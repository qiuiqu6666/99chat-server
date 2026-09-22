package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.chat99.server.auth.AuthProperties;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.notify.PlatformWalletNoticeService;
import com.chat99.server.notify.SystemNotifyService;
import com.chat99.server.sticker.StickerUserInitializer;
import com.chat99.server.user.GamePrivilegeService;
import com.chat99.server.user.LoginLog;
import com.chat99.server.user.LoginLogRepository;
import com.chat99.server.user.NicknameService;
import com.chat99.server.user.PlatformIdGenerator;
import com.chat99.server.user.User;
import com.chat99.server.user.UserDevice;
import com.chat99.server.user.UserDeviceRepository;
import com.chat99.server.user.UserLocationLatest;
import com.chat99.server.user.UserLocationLatestRepository;
import com.chat99.server.security.UserSessionService;
import com.chat99.server.user.UserDeviceService;
import com.chat99.server.user.UserFriend;
import com.chat99.server.user.UserFriendRepository;
import com.chat99.server.user.UserRepository;
import com.chat99.server.group.GroupMemberRepository;
import com.chat99.server.wallet.UserWallet;
import com.chat99.server.wallet.UserWalletRepository;
import com.chat99.server.wallet.WalletAccountService;
import com.chat99.server.wallet.WalletCurrency;
import com.chat99.server.wallet.WalletLedgerService;
import com.chat99.server.wallet.WalletLedgerType;
import com.chat99.server.wallet.WalletWithdrawalRepository;
import com.chat99.server.wallet.WithdrawalStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminUserManagementService {

    private final UserRepository userRepository;
    private final LoginLogRepository loginLogRepository;
    private final UserWalletRepository walletRepository;
    private final WalletWithdrawalRepository withdrawalRepository;
    private final WalletLedgerService ledgerService;
    private final WalletAccountService walletAccountService;
    private final UserDeviceService deviceService;
    private final UserSessionService sessionService;
    private final UserDeviceRepository deviceRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformIdGenerator platformIdGenerator;
    private final NicknameService nicknameService;
    private final ImAdminClient imAdmin;
    private final AuthProperties authProps;
    private final SystemNotifyService systemNotifyService;
    private final PlatformWalletNoticeService platformWalletNoticeService;
    private final StickerUserInitializer stickerUserInitializer;
    private final AdminApiProperties adminProps;
    private final AdminAuditService auditService;
    private final GamePrivilegeService gamePrivilegeService;
    private final UserLocationLatestRepository locationLatestRepository;
    private final UserFriendRepository userFriendRepository;
    private final GroupMemberRepository groupMemberRepository;

    @Lazy
    @Autowired
    private AdminUserManagementService self;

    public AdminUserManagementService(UserRepository userRepository,
                                      LoginLogRepository loginLogRepository,
                                      UserWalletRepository walletRepository,
                                      WalletWithdrawalRepository withdrawalRepository,
                                      WalletLedgerService ledgerService,
                                      WalletAccountService walletAccountService,
                                      UserDeviceService deviceService,
                                      UserSessionService sessionService,
                                      UserDeviceRepository deviceRepository,
                                      PasswordEncoder passwordEncoder,
                                      PlatformIdGenerator platformIdGenerator,
                                      NicknameService nicknameService,
                                      ImAdminClient imAdmin,
                                      AuthProperties authProps,
                                      SystemNotifyService systemNotifyService,
                                      PlatformWalletNoticeService platformWalletNoticeService,
                                      StickerUserInitializer stickerUserInitializer,
                                      AdminApiProperties adminProps,
                                      AdminAuditService auditService,
                                      GamePrivilegeService gamePrivilegeService,
                                      UserLocationLatestRepository locationLatestRepository,
                                      UserFriendRepository userFriendRepository,
                                      GroupMemberRepository groupMemberRepository) {
        this.userRepository = userRepository;
        this.loginLogRepository = loginLogRepository;
        this.walletRepository = walletRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.ledgerService = ledgerService;
        this.walletAccountService = walletAccountService;
        this.deviceService = deviceService;
        this.sessionService = sessionService;
        this.deviceRepository = deviceRepository;
        this.passwordEncoder = passwordEncoder;
        this.platformIdGenerator = platformIdGenerator;
        this.nicknameService = nicknameService;
        this.imAdmin = imAdmin;
        this.authProps = authProps;
        this.systemNotifyService = systemNotifyService;
        this.platformWalletNoticeService = platformWalletNoticeService;
        this.stickerUserInitializer = stickerUserInitializer;
        this.adminProps = adminProps;
        this.auditService = auditService;
        this.gamePrivilegeService = gamePrivilegeService;
        this.locationLatestRepository = locationLatestRepository;
        this.userFriendRepository = userFriendRepository;
        this.groupMemberRepository = groupMemberRepository;
    }

    public UserListResponse listUsers(int page, int pageSize, String keyword, String status,
                                      String isOnline, String sort,
                                      String gamePrivileged, String skipDeviceSms) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        String resolvedSort = sort != null && !sort.isBlank() ? sort.trim() : "register_time_desc";
        Specification<User> spec = buildSpec(keyword, status, isOnline, gamePrivileged, skipDeviceSms);
        PageRequest pr = PageRequest.of(safePage - 1, safeSize, resolveUserListSort(resolvedSort));
        Page<User> result = userRepository.findAll(spec, pr);
        List<User> users = result.getContent();
        List<String> userIds = users.stream().map(User::getUserId).toList();
        Map<String, UserLocationLatest> locations = userIds.isEmpty()
            ? Map.of()
            : locationLatestRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserLocationLatest::getUserId, Function.identity(), (a, b) -> a));
        Map<String, Integer> friendCounts = countFriendsLocal(userIds);
        Map<String, Integer> groupCounts = countGroupsLocal(userIds);
        List<UserListItem> items = users.stream()
            .map(u -> toListItem(
                u,
                null,
                locations.get(u.getUserId()),
                friendCounts.getOrDefault(u.getUserId(), 0),
                groupCounts.getOrDefault(u.getUserId(), 0)))
            .toList();
        return new UserListResponse(items, result.getTotalElements(), safePage, safeSize, resolvedSort);
    }

    private static Sort resolveUserListSort(String sort) {
        String key = sort == null ? "" : sort.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (key) {
            case "register_time_asc", "created_at_asc" ->
                Sort.by(Sort.Direction.ASC, "createdAt");
            // 注意：Specification + PageRequest 走 Criteria，不支持 Sort.NullHandling
            case "last_active_desc", "last_active_at_desc", "online_time_desc" ->
                Sort.by(Sort.Direction.DESC, "lastActiveAt")
                    .and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "last_active_asc", "last_active_at_asc", "online_time_asc" ->
                Sort.by(Sort.Direction.ASC, "lastActiveAt")
                    .and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "nickname_asc" -> Sort.by(Sort.Direction.ASC, "nickname");
            case "nickname_desc" -> Sort.by(Sort.Direction.DESC, "nickname");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }

    @Transactional
    public LoginDisabledResult setLoginDisabled(HttpServletRequest http, String adminUsername,
                                                String userUid, boolean disabled, boolean clearHttpToken) {
        User user = requireUser(userUid);
        if (disabled) {
            user.setStatus(0);
            user.setBypassDeviceCheck(false);
        } else {
            user.setStatus(1);
            // 解禁后允许立即登录：禁用时会清空信任设备，密码登录否则会一直走 NEED_SMS
            user.setBypassDeviceCheck(true);
        }
        userRepository.save(user);
        boolean cleared = false;
        if (disabled) {
            if (clearHttpToken) {
                deviceService.clearAllTrusted(user.getUserId());
                cleared = true;
            }
            sessionService.revokeAll(user.getUserId());
        }
        auditService.log(http, adminUsername, "user.login_disabled.set", user.getUserId(),
            Map.of("disabled", disabled, "clear_http_token", clearHttpToken));
        return new LoginDisabledResult(true, user.getUserId(), disabled, user.getStatus(), cleared);
    }

    @Transactional
    public GamePrivilegedResult setGamePrivileged(HttpServletRequest http, String adminUsername,
                                                  String userUid, boolean gamePrivileged) {
        GamePrivilegeService.GameAdminView view = gamePrivilegeService.setPrivileged(userUid, gamePrivileged);
        auditService.log(http, adminUsername, "user.game_privileged.set", userUid,
            Map.of("game_privileged", gamePrivileged));
        return new GamePrivilegedResult(
            true,
            userUid,
            view.gamePrivileged(),
            view.gameEnabledEffective(),
            view.masterEnabled());
    }

    @Transactional
    public SkipDeviceSmsResult setSkipDeviceSms(HttpServletRequest http, String adminUsername,
                                                String userUid, boolean enabled) {
        User user = requireUser(userUid);
        user.setSkipDeviceSms(enabled);
        userRepository.save(user);
        auditService.log(http, adminUsername, "user.skip_device_sms.set", user.getUserId(),
            Map.of("enabled", enabled));
        return new SkipDeviceSmsResult(true, user.getUserId(), user.isSkipDeviceSms());
    }

    @Transactional
    public OkUserResult resetLoginPassword(HttpServletRequest http, String adminUsername,
                                           String userUid, String newPassword) {
        validatePassword(newPassword);
        User user = requireUser(userUid);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        deviceService.clearAllTrusted(user.getUserId());
        sessionService.revokeAll(user.getUserId());
        auditService.log(http, adminUsername, "user.login_password.reset", user.getUserId(), Map.of());
        return new OkUserResult(true, user.getUserId());
    }

    @Transactional
    public OkUserResult resetFundPassword(HttpServletRequest http, String adminUsername,
                                          String userUid, String newFundPassword) {
        if (newFundPassword == null || !newFundPassword.matches("^\\d{6}$")) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "new_fund_password must be 6 digits");
        }
        User user = requireUser(userUid);
        UserWallet w = walletAccountService.ensureWallet(user);
        w.setPayPinHash(passwordEncoder.encode(newFundPassword));
        w.setPayPinFailCount(0);
        w.setPayPinLockedUntil(null);
        walletRepository.save(w);
        auditService.log(http, adminUsername, "user.fund_password.set", user.getUserId(), Map.of());
        return new OkUserResult(true, user.getUserId());
    }

    @Transactional
    public NicknameUpdateResult updateNickname(HttpServletRequest http, String adminUsername,
                                               String userUid, String rawNickname) {
        User user = requireUser(userUid);
        String nickname = nicknameService.normalize(rawNickname);
        try {
            nicknameService.validateFormat(nickname);
        } catch (ResponseStatusException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "invalid nickname format");
        }
        if (user.getNickname().equals(nickname)) {
            return new NicknameUpdateResult(true, user.getUserId(), nickname);
        }
        if (userRepository.existsByNicknameAndUserIdNot(nickname, user.getUserId())) {
            throw new AdminApiException(HttpStatus.CONFLICT, "duplicate_nickname", "昵称已存在");
        }

        String oldNickname = user.getNickname();
        user.setNickname(nickname);
        user.setLastNicknameChangedAt(Instant.now());
        userRepository.save(user);
        imAdmin.profileUpdate(user.getUserId(), nickname);
        auditService.log(http, adminUsername, "user.nickname.update", user.getUserId(),
            Map.of("old_nickname", oldNickname, "new_nickname", nickname));
        return new NicknameUpdateResult(true, user.getUserId(), nickname);
    }

    @Transactional
    public BalanceAdjustResult adjustBalance(HttpServletRequest http, String adminUsername,
                                             String userUid, String currencyRaw, String direction,
                                             String amount, String remark) {
        AdminAdjustCurrency currency;
        try {
            currency = AdminAdjustCurrency.parse(currencyRaw);
        } catch (IllegalArgumentException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", e.getMessage());
        }
        if (!"add".equals(direction) && !"subtract".equals(direction)) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid direction");
        }
        long units;
        try {
            units = AdminUserFormats.parseAdjustAmount(currency, amount);
        } catch (IllegalArgumentException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", e.getMessage());
        }

        User user = requireUser(userUid);
        UserWallet wallet = walletAccountService.ensureWallet(user);
        WalletCurrency bookCurrency = currency.ledgerBookCurrency();
        WalletLedgerType bookType = currency.ledgerTypeForAdjust(direction);
        String ledgerRemark = remark != null && !remark.isBlank() ? remark : "后台调账";
        long balanceBefore = AdminUserFormats.readAdjustBalance(wallet, currency);

        if ("subtract".equals(direction)) {
            long available = balanceBefore;
            if (currency == AdminAdjustCurrency.USDT) {
                long frozenMicro = withdrawalRepository.sumPendingAmountMicro(
                    user.getUserId(), List.of(WithdrawalStatus.PENDING));
                available = balanceBefore - frozenMicro;
            }
            if (available < units) {
                throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "insufficient_available_balance", "insufficient_available_balance");
            }
            try {
                ledgerService.debit(user.getUserId(), bookCurrency, units,
                    bookType, "ADMIN_ADJUST", null, null, ledgerRemark);
            } catch (ResponseStatusException e) {
                if ("INSUFFICIENT_BALANCE".equals(e.getReason())) {
                    throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "insufficient_available_balance", "insufficient_available_balance");
                }
                throw e;
            }
        } else {
            ledgerService.credit(user.getUserId(), bookCurrency, units,
                bookType, "ADMIN_ADJUST", null, null, ledgerRemark);
        }

        UserWallet updated = walletRepository.findById(user.getUserId()).orElse(wallet);
        long balanceAfter = AdminUserFormats.readAdjustBalance(updated, currency);
        String txNo = "TX" + System.currentTimeMillis();
        auditService.log(http, adminUsername, "user.wallet.balance_adjust", user.getUserId(),
            Map.of("currency", currency.name(), "direction", direction, "amount", amount,
                "transaction_no", txNo));
        return new BalanceAdjustResult(true, user.getUserId(), currency.name(), direction, amount,
            AdminUserFormats.formatAdjustBalance(currency, balanceBefore),
            AdminUserFormats.formatAdjustBalance(currency, balanceAfter), txNo);
    }

    @Transactional
    public CreateUserResult createUser(HttpServletRequest http, String adminUsername,
                                       String nickname, String password, String sex) {
        validatePassword(password);
        String normalized = nicknameService.normalize(nickname);
        nicknameService.validateFormat(normalized);
        if (userRepository.existsByNickname(normalized)) {
            throw new AdminApiException(HttpStatus.CONFLICT, "duplicate_nickname", "昵称已存在");
        }
        String userId = platformIdGenerator.allocate();
        User u = new User();
        u.setUserId(userId);
        u.setNickname(normalized);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setAvatarUrl(authProps.avatarUrl());
        u.setStatus(1);
        userRepository.save(u);

        afterUserCreated(u);
        CreateWalletResult walletResult = createWalletSafely(u);
        Map<String, Object> createAudit = new HashMap<>();
        createAudit.put("nickname", normalized);
        createAudit.put("sex", sex != null ? sex : "");
        auditService.log(http, adminUsername, "user.create", u.getUserId(), createAudit);
        return new CreateUserResult(true, u.getUserId(), normalized, null, null,
            walletResult.address(), walletResult.address(),
            walletResult.usdtContract(), walletResult.minDepositUsdt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public CreateWalletResult createWalletSafely(User u) {
        try {
            UserWallet wallet = walletAccountService.ensureWallet(u);
            WalletAccountService.WalletInfo winfo = walletAccountService.walletInfo(wallet);
            return new CreateWalletResult(wallet.getTronAddress(), winfo.usdtContract(), winfo.minDepositUsdt());
        } catch (Exception e) {
            return new CreateWalletResult(null, null, null);
        }
    }

    public record CreateWalletResult(String address, String usdtContract, String minDepositUsdt) {}

    @Transactional
    public BatchCreateResult createBatch(HttpServletRequest http, String adminUsername,
                                         String batchPasswordOrNull, List<BatchCreateItem> users) {
        if (users == null || users.isEmpty()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "users required");
        }
        if (users.size() > 100) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "max 100 users per batch");
        }
        validatePassword(batchPasswordOrNull);
        List<BatchCreateResultItem> items = new ArrayList<>();
        int success = 0;
        for (int i = 0; i < users.size(); i++) {
            BatchCreateItem in = users.get(i);
            try {
                CreateUserResult one = self.createUser(http, adminUsername, in.nickname(), batchPasswordOrNull, in.sex());
                items.add(new BatchCreateResultItem(i, true, one.userUid(), one.nickname(),
                    one.phoneNum(), one.trxAddress(), one.depositAddress(), one.usdtContract(),
                    one.minDepositUsdt(), null, null, null));
                success++;
            } catch (AdminApiException e) {
                items.add(new BatchCreateResultItem(i, false, null, null, null, null, null, null, null,
                    e.error(), e.getMessage(), fieldFromError(e.error())));
            } catch (Exception e) {
                items.add(new BatchCreateResultItem(i, false, null, null, null, null, null, null, null,
                    "internal_error", e.getMessage(), null));
            }
        }
        return new BatchCreateResult(items, users.size(), success, users.size() - success);
    }

    /**
     * 仅提交统一密码与数量，自动生成昵称并创建用户，返回账号清单。
     */
    public QuickCreateResult createByCount(HttpServletRequest http, String adminUsername,
                                           String password, int count, String sex) {
        if (count < 1 || count > 100) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "count must be between 1 and 100");
        }
        validatePassword(password);
        long batchSeed = System.currentTimeMillis();
        List<QuickCreateAccount> accounts = new ArrayList<>();
        int success = 0;
        for (int i = 0; i < count; i++) {
            try {
                String nickname = allocateAutoNickname(batchSeed, i);
                CreateUserResult one = self.createUser(http, adminUsername, nickname, password, sex);
                accounts.add(new QuickCreateAccount(
                    one.userUid(),
                    one.nickname(),
                    null,
                    null,
                    password,
                    one.trxAddress(),
                    one.depositAddress(),
                    one.usdtContract(),
                    one.minDepositUsdt()));
                success++;
            } catch (Exception ignored) {
                // 单条失败继续（如钱包未配置）；已成功条目仍返回
            }
        }
        auditService.log(http, adminUsername, "user.create_by_count", null,
            Map.of("requested", count, "success", success, "password_set", true));
        boolean ok = success == count;
        return new QuickCreateResult(ok, password, count, accounts, success, count - success);
    }

    /** 用户详情页钱包表：USDT/TRX/CNY 可用、冻结、充值地址。 */
    public UserWalletSummaryResponse getWalletSummary(String userUid) {
        User user = requireUser(userUid);
        UserWallet wallet = walletAccountService.ensureWallet(user);
        WalletAccountService.WalletInfo winfo = walletAccountService.walletInfo(wallet);
        long frozenUsdtMicro = withdrawalRepository.sumPendingAmountMicro(
            user.getUserId(), List.of(WithdrawalStatus.PENDING));
        String deposit = wallet.getTronAddress();
        List<WalletCurrencyRow> rows = List.of(
            walletCurrencyRow(AdminAdjustCurrency.USDT, wallet, frozenUsdtMicro, deposit),
            walletCurrencyRow(AdminAdjustCurrency.TRX, wallet, 0L, deposit),
            walletCurrencyRow(AdminAdjustCurrency.CNY, wallet, 0L, null));
        return new UserWalletSummaryResponse(
            user.getUserId(),
            deposit,
            deposit,
            winfo.usdtContract(),
            winfo.minDepositUsdt(),
            rows);
    }

    private static WalletCurrencyRow walletCurrencyRow(
        AdminAdjustCurrency currency, UserWallet wallet, long frozenUnits, String chainAddress) {
        long total = AdminUserFormats.readAdjustBalance(wallet, currency);
        long available = Math.max(0, total - frozenUnits);
        String address = currency == AdminAdjustCurrency.CNY ? null : chainAddress;
        String addressLabel = currency == AdminAdjustCurrency.CNY
            ? null
            : (chainAddress != null && !chainAddress.isBlank() ? "TRON 充值地址" : null);
        return new WalletCurrencyRow(
            currency.name(),
            AdminUserFormats.formatAdjustBalance(currency, available),
            AdminUserFormats.formatAdjustBalance(currency, frozenUnits),
            AdminUserFormats.formatAdjustBalance(currency, total),
            address,
            addressLabel);
    }

    public User requireUser(String userUid) {
        if (userUid == null || userUid.isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "user_uid required");
        }
        return userRepository.findByUserId(userUid.trim())
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "user_not_found", "user_not_found"));
    }

    private Specification<User> buildSpec(String keyword, String status, String isOnline,
                                          String gamePrivileged, String skipDeviceSms) {
        Instant onlineSince = Instant.now().minus(adminProps.onlineThresholdMinutes(), ChronoUnit.MINUTES);
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                List<Predicate> kwPreds = new ArrayList<>();
                kwPreds.add(cb.like(cb.lower(root.get("nickname")), kw));
                kwPreds.add(cb.like(root.get("phone"), "%" + keyword.trim() + "%"));
                kwPreds.add(cb.like(cb.lower(root.get("userId")), kw));
                try {
                    long id = Long.parseLong(keyword.trim());
                    kwPreds.add(cb.equal(root.get("id"), id));
                } catch (NumberFormatException ignored) {
                    // not numeric id
                }
                preds.add(cb.or(kwPreds.toArray(Predicate[]::new)));
            }
            if (status != null && !status.isBlank()) {
                preds.add(cb.equal(root.get("status"), Integer.parseInt(status.trim())));
            }
            if (isOnline != null && !isOnline.isBlank()) {
                if ("1".equals(isOnline.trim())) {
                    preds.add(cb.greaterThanOrEqualTo(root.get("lastActiveAt"), onlineSince));
                } else if ("0".equals(isOnline.trim())) {
                    preds.add(cb.or(
                        cb.isNull(root.get("lastActiveAt")),
                        cb.lessThan(root.get("lastActiveAt"), onlineSince)));
                }
            }
            if (gamePrivileged != null && !gamePrivileged.isBlank()) {
                if ("1".equals(gamePrivileged.trim()) || "true".equalsIgnoreCase(gamePrivileged.trim())) {
                    preds.add(cb.isTrue(root.get("gamePrivileged")));
                } else if ("0".equals(gamePrivileged.trim()) || "false".equalsIgnoreCase(gamePrivileged.trim())) {
                    preds.add(cb.isFalse(root.get("gamePrivileged")));
                }
            }
            if (skipDeviceSms != null && !skipDeviceSms.isBlank()) {
                if ("1".equals(skipDeviceSms.trim()) || "true".equalsIgnoreCase(skipDeviceSms.trim())) {
                    preds.add(cb.isTrue(root.get("skipDeviceSms")));
                } else if ("0".equals(skipDeviceSms.trim()) || "false".equalsIgnoreCase(skipDeviceSms.trim())) {
                    preds.add(cb.isFalse(root.get("skipDeviceSms")));
                }
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    /** 供详情页复用列表 profile 字段。 */
    public UserListItem toListItemPublic(User u, String imFaceUrl) {
        UserLocationLatest loc = locationLatestRepository.findById(u.getUserId()).orElse(null);
        int friendCount = (int) userFriendRepository.countByUserIdAndStatus(
            u.getUserId(), UserFriend.STATUS_ACTIVE);
        int groupCount = (int) groupMemberRepository.countMyGroups(u.getUserId());
        return toListItem(u, imFaceUrl, loc, friendCount, groupCount);
    }

    private Map<String, Integer> countFriendsLocal(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return toCountMap(userFriendRepository.countByUserIdsAndStatus(userIds, UserFriend.STATUS_ACTIVE));
    }

    private Map<String, Integer> countGroupsLocal(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return toCountMap(groupMemberRepository.countMyGroupsByUserIds(userIds));
    }

    private static Map<String, Integer> toCountMap(List<Object[]> rows) {
        Map<String, Integer> out = new HashMap<>();
        if (rows == null) {
            return out;
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            out.put(String.valueOf(row[0]), ((Number) row[1]).intValue());
        }
        return out;
    }

    private UserListItem toListItem(
        User u, String imFaceUrl, UserLocationLatest location, int friendCount, int groupCount) {
        Optional<LoginLog> latest = loginLogRepository.findFirstByUserIdAndSuccessTrueOrderByCreatedAtDesc(u.getUserId());
        Optional<LoginLog> first = loginLogRepository.findFirstByUserIdOrderByCreatedAtAsc(u.getUserId());
        UserWallet wallet = walletRepository.findById(u.getUserId()).orElse(null);
        long frozenMicro = wallet == null ? 0 : withdrawalRepository.sumPendingAmountMicro(
            u.getUserId(), List.of(WithdrawalStatus.PENDING));
        AdminUserFormats.WalletSnapshot snap = AdminUserFormats.walletSnapshot(wallet, frozenMicro);
        Instant onlineSince = Instant.now().minus(adminProps.onlineThresholdMinutes(), ChronoUnit.MINUTES);
        int online = u.getLastActiveAt() != null && !u.getLastActiveAt().isBefore(onlineSince) ? 1 : 0;
        String depositAddress = wallet != null ? wallet.getTronAddress() : null;
        String locationCityLabel = resolveLocationCityLabel(location);
        String lastActiveTime = AdminUserFormats.formatTime(u.getLastActiveAt());
        if (lastActiveTime == null) {
            lastActiveTime = latest.map(l -> AdminUserFormats.formatTime(l.getCreatedAt())).orElse(null);
        }
        return new UserListItem(
            u.getUserId(),
            null,
            u.getNickname(),
            2,
            stripPlus(u.getPhone()),
            first.map(LoginLog::getIp).orElse(null),
            AdminUserFormats.formatTime(u.getCreatedAt()),
            latest.map(l -> AdminUserFormats.formatTime(l.getCreatedAt())).orElse(null),
            latest.map(LoginLog::getIp).orElse(null),
            u.getStatus(),
            online,
            lastActiveTime,
            AdminUserFormats.resolveListAvatar(imFaceUrl, u.getAvatarUrl()),
            null,
            null,
            0,
            locationCityLabel,
            latest.map(l -> AdminUserFormats.mapDeviceType(l.getClientPlatform())).orElse(-1),
            resolveTrustedDeviceModel(u.getUserId()),
            null,
            AdminUserFormats.formatTime(u.getLastNicknameChangedAt()),
            u.getLastNicknameChangedAt() == null ? 0L : u.getLastNicknameChangedAt().getEpochSecond(),
            snap.walletBalance(),
            snap.walletFrozenAmount(),
            snap.walletBalances(),
            snap.walletFrozenByCurrency(),
            friendCount,
            groupCount,
            depositAddress,
            depositAddress,
            u.isGamePrivileged(),
            u.isSkipDeviceSms(),
            locationCityLabel);
    }

    private static String resolveLocationCityLabel(UserLocationLatest location) {
        if (location == null) {
            return null;
        }
        if (location.getCityLabel() != null && !location.getCityLabel().isBlank()) {
            return location.getCityLabel().trim();
        }
        StringBuilder sb = new StringBuilder();
        appendLocPart(sb, location.getProvince());
        appendLocPart(sb, location.getCity());
        appendLocPart(sb, location.getDistrict());
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendLocPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(part.trim());
    }

    private void afterUserCreated(User u) {
        imAdmin.accountImport(u.getUserId(), u.getNickname(), u.getAvatarUrl());
        stickerUserInitializer.ensureInstalled(u.getUserId());
        systemNotifyService.onUserRegistered(u.getUserId());
        platformWalletNoticeService.onUserRegistered(u.getUserId());
    }

    private String allocateAutoNickname(long batchSeed, int index) {
        for (int attempt = 0; attempt < 30; attempt++) {
            String nick = String.format("用户%06d%03d", batchSeed % 1_000_000, index + 1 + attempt);
            if (!userRepository.existsByNickname(nick)) {
                nicknameService.validateFormat(nick);
                return nick;
            }
        }
        throw new AdminApiException(HttpStatus.CONFLICT, "duplicate_nickname", "failed to allocate nickname");
    }

    private static String stripPlus(String phone) {
        if (phone == null) {
            return null;
        }
        return phone.startsWith("+") ? phone.substring(1) : phone;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 128) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                "password must be 6-128 characters");
        }
    }

    private String resolveTrustedDeviceModel(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        List<UserDevice> trusted = deviceRepository.findByUserIdAndTrustedTrue(userId);
        if (trusted.isEmpty()) {
            return deviceRepository.findByUserIdOrderByLastLoginAtDesc(userId).stream()
                .map(UserDevice::getModel)
                .filter(model -> model != null && !model.isBlank())
                .findFirst()
                .orElse(null);
        }
        return trusted.stream()
            .map(UserDevice::getModel)
            .filter(model -> model != null && !model.isBlank())
            .findFirst()
            .orElse(null);
    }

    private static String fieldFromError(String error) {
        return "duplicate_nickname".equals(error) ? "nickname" : null;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UserListItem(
        String userUid,
        String userMail,
        String nickname,
        Integer userSex,
        String phoneNum,
        String registerIp,
        String registerTime,
        String latestLoginTime,
        String latestLoginIp,
        Integer userStatus,
        Integer isOnline,
        String lastActiveTime,
        String userAvatarFileName,
        String whatSUp,
        String userDesc,
        Integer userType,
        String userRegieon,
        Integer deviceType,
        String deviceModel,
        String terminationTime,
        String nicknameLastModifiedTime,
        Long nicknameLastModifiedTime2,
        String walletBalance,
        String walletFrozenAmount,
        java.util.Map<String, String> walletBalances,
        java.util.Map<String, String> walletFrozenByCurrency,
        Integer friendCount,
        Integer groupCount,
        String trxAddress,
        String depositAddress,
        boolean gamePrivileged,
        boolean skipDeviceSms,
        /** 最新上报位置的城市文案（逆地理），不对前端暴露精确经纬度 */
        String locationCityLabel) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SkipDeviceSmsResult(
        boolean ok,
        String userUid,
        boolean skipDeviceSms) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GamePrivilegedResult(
        boolean ok,
        String userUid,
        boolean gamePrivileged,
        boolean gameEnabledEffective,
        boolean masterEnabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UserListResponse(
        List<UserListItem> items,
        long total,
        int page,
        int pageSize,
        String sort) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoginDisabledResult(
        boolean ok,
        String userUid,
        boolean disabled,
        int userStatus,
        boolean httpTokenCleared) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OkUserResult(boolean ok, String userUid) {}

    public record NicknameUpdateResult(boolean ok, String userUid, String nickname) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BalanceAdjustResult(
        boolean ok,
        String userUid,
        String currency,
        String direction,
        String amount,
        String balanceBefore,
        String balanceAfter,
        String transactionNo) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateUserResult(
        boolean ok,
        String userUid,
        String nickname,
        String phone,
        String phoneNum,
        String trxAddress,
        String depositAddress,
        String usdtContract,
        String minDepositUsdt) {}

    public record BatchCreateItem(String nickname, String sex) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BatchCreateResultItem(
        int index,
        boolean ok,
        String userUid,
        String nickname,
        String phoneNum,
        String trxAddress,
        String depositAddress,
        String usdtContract,
        String minDepositUsdt,
        String error,
        String message,
        String field) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BatchCreateResult(
        List<BatchCreateResultItem> items,
        int total,
        int successCount,
        int failCount) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record QuickCreateAccount(
        String userUid,
        String nickname,
        String phone,
        String phoneNum,
        String password,
        String trxAddress,
        String depositAddress,
        String usdtContract,
        String minDepositUsdt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record QuickCreateResult(
        boolean ok,
        String password,
        int count,
        List<QuickCreateAccount> accounts,
        int successCount,
        int failCount) {}

    /** 详情页钱包三行（USDT / TRX / CNY）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UserWalletSummaryResponse(
        String userUid,
        String depositAddress,
        String trxAddress,
        String usdtContract,
        String minDepositUsdt,
        List<WalletCurrencyRow> currencies) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WalletCurrencyRow(
        String currency,
        String balanceAvailable,
        String balanceFrozen,
        String balanceTotal,
        String walletAddress,
        String walletAddressLabel) {}
}
