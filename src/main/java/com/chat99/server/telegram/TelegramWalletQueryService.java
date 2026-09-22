package com.chat99.server.telegram;

import com.chat99.server.user.DeviceModelDisplayService;
import com.chat99.server.user.LoginLog;
import com.chat99.server.user.LoginLogRepository;
import com.chat99.server.user.User;
import com.chat99.server.user.UserDevice;
import com.chat99.server.user.UserDeviceRepository;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.DepositStatus;
import com.chat99.server.wallet.TronGridClient;
import com.chat99.server.wallet.UserWallet;
import com.chat99.server.wallet.UserWalletRepository;
import com.chat99.server.wallet.WalletChainBalanceService;
import com.chat99.server.wallet.WalletDepositRepository;
import com.chat99.server.wallet.WalletWithdrawalRepository;
import com.chat99.server.wallet.WithdrawalStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class TelegramWalletQueryService {

    /** 平台 userId：10 位字母数字。 */
    private static final Pattern USER_ID = Pattern.compile("(?i)(?<![a-z0-9])@?([a-z0-9]{10})(?![a-z0-9])");
    /** 手机号：可选 +，7～15 位数字。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)\\+?\\d{7,15}(?!\\d)");
    private static final Pattern QUERY_COMMAND = Pattern.compile(
        "(?i)^/(?:bal|balance|wallet|uid|user)(?:@\\w+)?(?:\\s+(.+))?$");
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private static final List<WithdrawalStatus> OPEN_WITHDRAW =
        List.of(WithdrawalStatus.PENDING, WithdrawalStatus.BROADCASTING, WithdrawalStatus.CONFIRMING);

    private final TelegramOpsProperties props;
    private final UserRepository userRepository;
    private final UserWalletRepository walletRepository;
    private final WalletChainBalanceService chainBalanceService;
    private final WalletWithdrawalRepository withdrawalRepository;
    private final WalletDepositRepository depositRepository;
    private final LoginLogRepository loginLogRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final DeviceModelDisplayService modelDisplay;

    public TelegramWalletQueryService(TelegramOpsProperties props,
                                      UserRepository userRepository,
                                      UserWalletRepository walletRepository,
                                      WalletChainBalanceService chainBalanceService,
                                      WalletWithdrawalRepository withdrawalRepository,
                                      WalletDepositRepository depositRepository,
                                      LoginLogRepository loginLogRepository,
                                      UserDeviceRepository userDeviceRepository,
                                      DeviceModelDisplayService modelDisplay) {
        this.props = props;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.chainBalanceService = chainBalanceService;
        this.withdrawalRepository = withdrawalRepository;
        this.depositRepository = depositRepository;
        this.loginLogRepository = loginLogRepository;
        this.userDeviceRepository = userDeviceRepository;
        this.modelDisplay = modelDisplay;
    }

    /**
     * 从群消息中解析要查询的 userId。
     * 支持：{@code rqwm8onw3j}、{@code @rqwm8onw3j}、手机号、{@code /bal ...}。
     */
    public List<String> extractUserIds(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String trimmed = text.trim();
        Matcher cmd = QUERY_COMMAND.matcher(trimmed);
        String scan = trimmed;
        if (cmd.matches()) {
            String args = cmd.group(1);
            if (args == null || args.isBlank()) {
                return List.of();
            }
            scan = args;
        } else if (trimmed.startsWith("/")) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = USER_ID.matcher(scan);
        while (m.find()) {
            ids.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher phones = PHONE.matcher(scan);
        while (phones.find()) {
            resolvePhoneToUserId(phones.group()).ifPresent(ids::add);
        }
        return new ArrayList<>(ids);
    }

    public boolean shouldHandleMessage(String chatId, String text, boolean fromBot) {
        if (!props.isQueryReady() || fromBot) {
            return false;
        }
        if (chatId == null || !chatId.equals(props.chatId().trim())) {
            return false;
        }
        return !extractUserIds(text).isEmpty();
    }

    public String buildReply(String text) {
        List<String> ids = extractUserIds(text);
        if (ids.isEmpty()) {
            // 输入了像手机号但未命中用户
            if (text != null && PHONE.matcher(text.trim()).find()) {
                return "❓ 未找到手机号对应的用户";
            }
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append("\n\n——————\n\n");
            }
            sb.append(buildOne(ids.get(i)));
        }
        return sb.toString();
    }

    private Optional<String> resolvePhoneToUserId(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return Optional.empty();
        }
        String phone = rawPhone.trim();
        Optional<User> user = userRepository.findByPhone(phone);
        if (user.isPresent()) {
            return user.map(User::getUserId);
        }
        if (phone.startsWith("+")) {
            user = userRepository.findByPhone(phone.substring(1));
            if (user.isPresent()) {
                return user.map(User::getUserId);
            }
        } else {
            user = userRepository.findByPhone("+" + phone);
            if (user.isPresent()) {
                return user.map(User::getUserId);
            }
        }
        // 国内 11 位手机号常见存成 +86...
        if (phone.matches("1\\d{10}")) {
            user = userRepository.findByPhone("+86" + phone);
            if (user.isPresent()) {
                return user.map(User::getUserId);
            }
        }
        if (phone.matches("86\\d{11}")) {
            user = userRepository.findByPhone("+" + phone);
            if (user.isPresent()) {
                return user.map(User::getUserId);
            }
        }
        return Optional.empty();
    }

    private String buildOne(String userId) {
        Optional<User> userOpt = userRepository.findByUserId(userId);
        Optional<UserWallet> walletOpt = walletRepository.findById(userId);
        if (userOpt.isEmpty() && walletOpt.isEmpty()) {
            return "❓ 用户 <code>" + esc(userId) + "</code> 不存在";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<b>👤 用户资料</b>\n");
        sb.append("用户ID: <code>").append(esc(userId)).append("</code>\n");

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            sb.append("昵称: ").append(esc(nullToDash(user.getNickname()))).append("\n");
            sb.append("手机: <code>").append(esc(formatPhone(user))).append("</code>\n");
            sb.append("账号状态: ").append(accountStatusLabel(user.getStatus())).append("\n");
            sb.append("注册时间: ").append(fmtTime(user.getCreatedAt())).append("\n");
            sb.append("最后活跃: ").append(fmtTime(user.getLastActiveAt())).append("\n");
            sb.append("游戏特权: ").append(user.isGamePrivileged() ? "是" : "否").append("\n");
            sb.append("跳过设备短信: ").append(user.isSkipDeviceSms() ? "是" : "否").append("\n");
        } else {
            sb.append("昵称: -\n");
            sb.append("手机: -\n");
            sb.append("账号状态: 用户记录缺失\n");
        }

        if (walletOpt.isEmpty()) {
            sb.append("\n钱包: 未创建");
            appendLoginSection(sb, userId);
            return sb.toString();
        }

        UserWallet wallet = walletOpt.get();
        TronGridClient.AccountBalances live = null;
        try {
            live = chainBalanceService.refreshAndSave(wallet).orElse(null);
        } catch (Exception ignored) {
            // 链上刷新失败时回退缓存
        }
        wallet = walletRepository.findById(userId).orElse(wallet);

        long chainUsdt = live != null ? live.usdtMicro() : wallet.getChainUsdtMicro();
        long chainTrx = live != null ? live.trxSun() : wallet.getChainTrxSun();
        boolean payPinSet = wallet.getPayPinHash() != null && !wallet.getPayPinHash().isBlank();
        boolean payPinLocked = wallet.getPayPinLockedUntil() != null
            && wallet.getPayPinLockedUntil().isAfter(java.time.Instant.now());

        sb.append("支付密码: ").append(payPinSet ? "已设置" : "未设置");
        if (payPinLocked) {
            sb.append("（已锁定至 ").append(fmtTime(wallet.getPayPinLockedUntil())).append("）");
        } else if (payPinSet && wallet.getPayPinFailCount() > 0) {
            sb.append("（失败 ").append(wallet.getPayPinFailCount()).append(" 次）");
        }
        sb.append("\n");

        sb.append("\n<b>💰 平台余额</b>\n");
        sb.append("USDT: <b>").append(formatUsdt(wallet.getBalanceUsdtMicro())).append("</b>\n");
        sb.append("99币: <b>").append(formatPlatform(wallet.getBalancePlatformFen())).append("</b>\n");

        sb.append("\n<b>🔗 链上余额</b>").append(live != null ? "（实时）" : "（缓存）").append("\n");
        sb.append("充值地址: <code>").append(esc(wallet.getTronAddress())).append("</code>\n");
        sb.append("USDT: <b>").append(formatUsdt(chainUsdt)).append("</b>\n");
        sb.append("TRX: ").append(formatTrx(chainTrx)).append("\n");
        sb.append("同步时间: ").append(fmtTime(wallet.getChainBalanceAt())).append("\n");

        long pendingWithdrawMicro = withdrawalRepository.sumPendingAmountMicro(userId, OPEN_WITHDRAW);
        long pendingWithdrawCount = withdrawalRepository.countByUserIdAndStatusIn(userId, OPEN_WITHDRAW);
        long confirmingDepositCount = depositRepository.countByUserIdAndStatus(userId, DepositStatus.CONFIRMING);
        sb.append("\n<b>📋 资金状态</b>\n");
        sb.append("待处理提现: ").append(pendingWithdrawCount).append(" 笔 / ")
            .append(formatUsdt(pendingWithdrawMicro)).append("\n");
        sb.append("确认中充值: ").append(confirmingDepositCount).append(" 笔\n");

        appendLoginSection(sb, userId);
        return sb.toString();
    }

    private static final int MAX_IP_LIST = 12;
    private static final int MAX_VERSION_LIST = 8;

    private void appendLoginSection(StringBuilder sb, String userId) {
        sb.append("\n<b>🔐 登录信息</b>\n");
        Optional<LoginLog> lastLogin = loginLogRepository.findFirstByUserIdAndSuccessTrueOrderByCreatedAtDesc(userId);
        if (lastLogin.isPresent()) {
            LoginLog log = lastLogin.get();
            sb.append("最近登录: ").append(fmtTime(log.getCreatedAt())).append("\n");
            sb.append("最近IP: <code>").append(esc(nullToDash(log.getIp()))).append("</code>\n");
            sb.append("最近端: ").append(esc(nullToDash(log.getClientPlatform())));
            if (log.getClientVersion() != null && !log.getClientVersion().isBlank()) {
                sb.append(" ").append(esc(log.getClientVersion()));
            }
            sb.append("\n");
            if (log.getDeviceId() != null && !log.getDeviceId().isBlank()) {
                sb.append("最近设备: <code>").append(esc(shortDevice(log.getDeviceId()))).append("</code>\n");
            }
        } else {
            sb.append("最近登录: 无记录\n");
        }

        List<String> ips = loginLogRepository.findDistinctSuccessIpsByUserId(userId);
        List<String> devices = loginLogRepository.findDistinctDeviceIdsByUserId(userId);
        List<String> versions = loginLogRepository.findDistinctSuccessVersionsByUserId(userId);
        List<String> platforms = loginLogRepository.findDistinctSuccessPlatformsByUserId(userId);
        List<String> displayIps = orderedIps(userId, ips);

        sb.append("历史IP数: ").append(ips.size()).append("\n");
        if (!displayIps.isEmpty()) {
            sb.append("历史IP: ");
            int show = Math.min(displayIps.size(), MAX_IP_LIST);
            for (int i = 0; i < show; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("<code>").append(esc(displayIps.get(i))).append("</code>");
            }
            if (ips.size() > show) {
                sb.append(" …共").append(ips.size()).append("个");
            }
            sb.append("\n");
        }

        sb.append("历史设备数: ").append(devices.size()).append("\n");
        if (!platforms.isEmpty()) {
            sb.append("历史端: ").append(esc(String.join(", ", platforms))).append("\n");
        }
        if (!versions.isEmpty()) {
            sb.append("历史版本: ");
            int show = Math.min(versions.size(), MAX_VERSION_LIST);
            List<String> shown = versions.stream().limit(show).collect(Collectors.toList());
            sb.append(esc(String.join(", ", shown)));
            if (versions.size() > show) {
                sb.append(" …共").append(versions.size()).append("个");
            }
            sb.append("\n");
        } else {
            sb.append("历史版本: -\n");
        }

        List<UserDevice> allDevices = userDeviceRepository.findByUserIdOrderByLastLoginAtDesc(userId);
        List<UserDevice> trusted = allDevices.stream().filter(UserDevice::isTrusted).toList();
        sb.append("登记设备: ").append(allDevices.size())
            .append("（信任 ").append(trusted.size()).append("）");
        if (trusted.isEmpty()) {
            sb.append("\n信任设备: 无");
        } else {
            UserDevice d = trusted.get(0);
            sb.append("\n信任设备: ").append(esc(formatTrustedDevice(d)));
            if (trusted.size() > 1) {
                sb.append("（共 ").append(trusted.size()).append(" 台）");
            } else {
                sb.append("（共 1 台）");
            }
        }
    }

    private String formatTrustedDevice(UserDevice d) {
        String platform = platformLabel(d.getPlatform());
        String modelName = modelDisplay.display(d.getPlatform(), d.getModel());
        if (modelName == null || modelName.isBlank() || modelName.equalsIgnoreCase(platform)) {
            return platform;
        }
        // display() 已是可读型号（如 iPhone 17 Pro Max），避免再拼重复平台前缀
        if (modelName.toLowerCase(Locale.ROOT).startsWith(platform.toLowerCase(Locale.ROOT))) {
            return modelName;
        }
        return platform + " / " + modelName;
    }

    private static String platformLabel(String platform) {
        if (platform == null || platform.isBlank()) {
            return "-";
        }
        return switch (platform.toLowerCase(Locale.ROOT)) {
            case "ios" -> "iOS";
            case "android" -> "Android";
            case "web" -> "Web";
            default -> platform;
        };
    }

    /** 最近登录 IP 优先，再补全其余历史 IP。 */
    private List<String> orderedIps(String userId, List<String> allIps) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        List<LoginLog> recent = loginLogRepository
            .findByUserIdAndSuccessTrueOrderByCreatedAtDesc(userId, PageRequest.of(0, 80))
            .getContent();
        for (LoginLog log : recent) {
            if (log.getIp() != null && !log.getIp().isBlank()) {
                ordered.add(log.getIp().trim());
            }
        }
        if (allIps != null) {
            for (String ip : allIps) {
                if (ip != null && !ip.isBlank()) {
                    ordered.add(ip.trim());
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    private static String formatPhone(User user) {
        String phone = user.getPhone();
        if (phone == null || phone.isBlank()) {
            return "-";
        }
        String country = user.getPhoneCountry();
        if (country != null && !country.isBlank() && !phone.startsWith("+")) {
            return "+" + country + " " + phone;
        }
        return phone;
    }

    private static String accountStatusLabel(int status) {
        return switch (status) {
            case 1 -> "正常";
            case 0 -> "禁用";
            default -> "未知(" + status + ")";
        };
    }

    private static String fmtTime(java.time.Instant instant) {
        if (instant == null) {
            return "-";
        }
        return TIME_FMT.format(instant);
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String shortDevice(String deviceId) {
        if (deviceId.length() <= 16) {
            return deviceId;
        }
        return deviceId.substring(0, 8) + "…" + deviceId.substring(deviceId.length() - 4);
    }

    static String formatUsdt(long micro) {
        return BigDecimal.valueOf(micro)
            .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP)
            .toPlainString() + " USDT";
    }

    static String formatPlatform(long fen) {
        return BigDecimal.valueOf(fen)
            .divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP)
            .toPlainString() + " 99";
    }

    static String formatTrx(long sun) {
        return BigDecimal.valueOf(sun)
            .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP)
            .toPlainString() + " TRX";
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
