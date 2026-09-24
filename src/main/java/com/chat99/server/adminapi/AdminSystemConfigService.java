package com.chat99.server.adminapi;

import com.chat99.server.common.AppSettingService;
import com.chat99.server.group.GroupCreateLimitConfigService;
import com.chat99.server.oss.OssClient;
import com.chat99.server.platform.PlatformConfigService;
import com.chat99.server.push.PushConfigService;
import com.chat99.server.wallet.WalletConfigService;
import com.chat99.server.wallet.WalletCurrency;
import com.chat99.server.wallet.WalletLimitConfig;
import com.chat99.server.wallet.WalletLimitConfigRepository;
import com.chat99.server.wallet.WalletLimitScene;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSystemConfigService {

    private static final Set<String> INFRA_KEYS = Set.of(
        AppSettingService.JWT_SECRET,
        AppSettingService.IM_SDK_APP_ID,
        AppSettingService.IM_KEY,
        AppSettingService.SMSBAO_USER,
        AppSettingService.SMSBAO_PWD_MD5,
        AppSettingService.OSS_ENDPOINT,
        AppSettingService.OSS_BUCKET,
        AppSettingService.OSS_ACCESS_KEY_ID,
        AppSettingService.OSS_ACCESS_KEY_SECRET,
        AppSettingService.OSS_CDN_DOMAIN);

    private static final Set<String> SECRET_KEYS = Set.of(
        AppSettingService.JWT_SECRET,
        AppSettingService.IM_KEY,
        AppSettingService.SMSBAO_PWD_MD5,
        AppSettingService.OSS_ACCESS_KEY_SECRET);

    private static final Map<String, String> INFRA_LABELS = Map.ofEntries(
        Map.entry(AppSettingService.JWT_SECRET, "JWT 签名密钥"),
        Map.entry(AppSettingService.IM_SDK_APP_ID, " IM SDKAppId"),
        Map.entry(AppSettingService.IM_KEY, " IM 密钥"),
        Map.entry(AppSettingService.SMSBAO_USER, "短信宝账号"),
        Map.entry(AppSettingService.SMSBAO_PWD_MD5, "短信宝密码 MD5"),
        Map.entry(AppSettingService.OSS_ENDPOINT, "OSS Endpoint"),
        Map.entry(AppSettingService.OSS_BUCKET, "OSS Bucket"),
        Map.entry(AppSettingService.OSS_ACCESS_KEY_ID, "OSS AccessKeyId"),
        Map.entry(AppSettingService.OSS_ACCESS_KEY_SECRET, "OSS AccessKeySecret"),
        Map.entry(AppSettingService.OSS_CDN_DOMAIN, "OSS CDN 域名"));

    private final PlatformConfigService platformConfigService;
    private final PushConfigService pushConfigService;
    private final GroupCreateLimitConfigService groupCreateLimitConfigService;
    private final WalletConfigService walletConfigService;
    private final WalletLimitConfigRepository walletLimitConfigRepository;
    private final AppSettingService appSettingService;
    private final OssClient ossClient;

    public AdminSystemConfigService(PlatformConfigService platformConfigService,
                                    PushConfigService pushConfigService,
                                    GroupCreateLimitConfigService groupCreateLimitConfigService,
                                    WalletConfigService walletConfigService,
                                    WalletLimitConfigRepository walletLimitConfigRepository,
                                    AppSettingService appSettingService,
                                    OssClient ossClient) {
        this.platformConfigService = platformConfigService;
        this.pushConfigService = pushConfigService;
        this.groupCreateLimitConfigService = groupCreateLimitConfigService;
        this.walletConfigService = walletConfigService;
        this.walletLimitConfigRepository = walletLimitConfigRepository;
        this.appSettingService = appSettingService;
        this.ossClient = ossClient;
    }

    public PlatformConfigView getPlatform() {
        return new PlatformConfigView(
            platformConfigService.getWebsite(),
            platformConfigService.getEmail(),
            platformConfigService.getCustomerServiceUrl(),
            platformConfigService.getFeedbackPrefix(),
            platformConfigService.getMaxFeedbackScreenshots(),
            platformConfigService.getMaxFeedbackContentLength());
    }

    public PlatformConfigView updatePlatform(UpdatePlatformRequest req) {
        if (req.website() != null) {
            platformConfigService.setWebsite(trimOrNull(req.website()));
        }
        if (req.email() != null) {
            platformConfigService.setEmail(trimOrNull(req.email()));
        }
        if (req.customerServiceUrl() != null) {
            platformConfigService.setCustomerServiceUrl(trimOrNull(req.customerServiceUrl()));
        }
        if (req.feedbackPrefix() != null) {
            platformConfigService.setFeedbackPrefix(trimOrNull(req.feedbackPrefix()));
        }
        if (req.maxFeedbackScreenshots() != null) {
            int v = req.maxFeedbackScreenshots();
            if (v < 1 || v > 20) {
                throw validation("max_feedback_screenshots must be 1..20");
            }
            platformConfigService.setMaxFeedbackScreenshots(v);
        }
        if (req.maxFeedbackContentLength() != null) {
            int v = req.maxFeedbackContentLength();
            if (v < 100 || v > 10000) {
                throw validation("max_feedback_content_length must be 100..10000");
            }
            platformConfigService.setMaxFeedbackContentLength(v);
        }
        return getPlatform();
    }

    public PushBusinessConfigView getPushBusiness() {
        return new PushBusinessConfigView(
            pushConfigService.isPushEnabled(),
            pushConfigService.isSkipWhenOnline(),
            pushConfigService.isVoipPushEnabled(),
            pushConfigService.isJpushEnabled(),
            maskSecret(pushConfigService.getJpushAppKey()),
            maskSecret(pushConfigService.getJpushMasterSecret()),
            pushConfigService.getJpushBaseUrl(),
            pushConfigService.isImCallbackEnabled(),
            pushConfigService.isChatPushEnabled(),
            maskSecret(pushConfigService.getCallbackToken()),
            pushConfigService.getAllowedSdkAppIds(),
            pushConfigService.isChatPushSkipWhenOnline(),
            String.join(",", pushConfigService.getSkipSenderIds()),
            pushConfigService.getMaxGroupMembersPerPush(),
            pushConfigService.getDedupTtlHours(),
            walletConfigService.getPayPinMaxFailures(),
            walletConfigService.getPayPinLockMinutes(),
            walletConfigService.getRedPacketExpireHours(),
            walletConfigService.getMinDepositUsdtMicro(),
            walletConfigService.getDepositConfirmations(),
            walletConfigService.getDepositMode(),
            walletConfigService.isDepositMnemonicConfigured(),
            walletConfigService.isHotWalletConfigured(),
            maskSecret(walletConfigService.getTrongridApiKey()),
            listWalletLimits(),
            groupCreateLimitConfigService.isEnabled(),
            groupCreateLimitConfigService.getMaxJoinGroups(),
            groupCreateLimitConfigService.getMaxCommunityJoinGroups(),
            groupCreateLimitConfigService.getMaxCommunityGroups(),
            groupCreateLimitConfigService.isEnforce(),
            groupCreateLimitConfigService.isLogOnly(),
            groupCreateLimitConfigService.isUseImCountFallback(),
            groupCreateLimitConfigService.getCommunityPriceCurrency().getApiCode(),
            groupCreateLimitConfigService.getCommunityPriceMinor());
    }

    @Transactional
    public PushBusinessConfigView updatePushBusiness(UpdatePushBusinessRequest req) {
        if (req.pushEnabled() != null) {
            pushConfigService.setPushEnabled(req.pushEnabled());
        }
        if (req.skipWhenOnline() != null) {
            pushConfigService.setSkipWhenOnline(req.skipWhenOnline());
        }
        if (req.voipPushEnabled() != null) {
            pushConfigService.setVoipPushEnabled(req.voipPushEnabled());
        }
        if (req.jpushEnabled() != null) {
            pushConfigService.setJpushEnabled(req.jpushEnabled());
        }
        if (req.jpushAppKey() != null && !req.jpushAppKey().isBlank()) {
            pushConfigService.setJpushAppKey(req.jpushAppKey().trim());
        }
        if (req.jpushMasterSecret() != null && !req.jpushMasterSecret().isBlank()) {
            pushConfigService.setJpushMasterSecret(req.jpushMasterSecret().trim());
        }
        if (req.jpushBaseUrl() != null) {
            pushConfigService.setJpushBaseUrl(trimOrNull(req.jpushBaseUrl()));
        }
        if (req.imCallbackEnabled() != null) {
            pushConfigService.setImCallbackEnabled(req.imCallbackEnabled());
        }
        if (req.chatPushEnabled() != null) {
            pushConfigService.setChatPushEnabled(req.chatPushEnabled());
        }
        if (req.callbackToken() != null && !req.callbackToken().isBlank()) {
            pushConfigService.setCallbackToken(req.callbackToken().trim());
        }
        if (req.allowedSdkAppIds() != null) {
            pushConfigService.setAllowedSdkAppIds(trimOrNull(req.allowedSdkAppIds()));
        }
        if (req.chatPushSkipWhenOnline() != null) {
            pushConfigService.setChatPushSkipWhenOnline(req.chatPushSkipWhenOnline());
        }
        if (req.skipSenderIds() != null) {
            pushConfigService.setSkipSenderIds(trimOrNull(req.skipSenderIds()));
        }
        if (req.maxGroupMembersPerPush() != null) {
            pushConfigService.setMaxGroupMembersPerPush(req.maxGroupMembersPerPush());
        }
        if (req.dedupTtlHours() != null) {
            pushConfigService.setDedupTtlHours(req.dedupTtlHours());
        }
        if (req.payPinMaxFailures() != null) {
            walletConfigService.setPayPinMaxFailures(req.payPinMaxFailures());
        }
        if (req.payPinLockMinutes() != null) {
            walletConfigService.setPayPinLockMinutes(req.payPinLockMinutes());
        }
        if (req.redPacketExpireHours() != null) {
            walletConfigService.setRedPacketExpireHours(req.redPacketExpireHours());
        }
        if (req.minDepositUsdtMicro() != null) {
            walletConfigService.setMinDepositUsdtMicro(req.minDepositUsdtMicro());
        }
        if (req.depositConfirmations() != null) {
            walletConfigService.setDepositConfirmations(req.depositConfirmations());
        }
        if (req.depositMode() != null) {
            String mode = req.depositMode().trim();
            if (!mode.equals("address-poll") && !mode.equals("block-scan")) {
                throw validation("deposit_mode must be address-poll or block-scan");
            }
            walletConfigService.setDepositMode(mode);
        }
        if (req.trongridApiKey() != null && !req.trongridApiKey().isBlank()) {
            walletConfigService.setTrongridApiKey(req.trongridApiKey().trim());
        }
        if (req.walletLimits() != null) {
            for (UpdateWalletLimitItem item : req.walletLimits()) {
                updateWalletLimit(item);
            }
        }
        if (req.groupCreateLimitEnabled() != null) {
            groupCreateLimitConfigService.setEnabled(req.groupCreateLimitEnabled());
        }
        if (req.groupJoinLimitMax() != null) {
            int v = req.groupJoinLimitMax();
            if (v < 0 || v > 10000) {
                throw validation("group_join_limit_max must be 0..10000");
            }
            groupCreateLimitConfigService.setMaxJoinGroups(v);
        }
        if (req.groupJoinLimitMaxCommunity() != null) {
            int v = req.groupJoinLimitMaxCommunity();
            if (v < 0 || v > 10000) {
                throw validation("group_join_limit_max_community must be 0..10000");
            }
            groupCreateLimitConfigService.setMaxCommunityJoinGroups(v);
        }
        if (req.groupCreateLimitMaxCommunity() != null) {
            int v = req.groupCreateLimitMaxCommunity();
            if (v < 0 || v > 1000) {
                throw validation("group_create_limit_max_community must be 0..1000");
            }
            groupCreateLimitConfigService.setMaxCommunityGroups(v);
        }
        if (req.groupCreateLimitEnforce() != null) {
            groupCreateLimitConfigService.setEnforce(req.groupCreateLimitEnforce());
        }
        if (req.groupCreateLimitLogOnly() != null) {
            groupCreateLimitConfigService.setLogOnly(req.groupCreateLimitLogOnly());
        }
        if (req.groupCreateLimitUseImCountFallback() != null) {
            groupCreateLimitConfigService.setUseImCountFallback(req.groupCreateLimitUseImCountFallback());
        }
        if (req.communityCreatePriceCurrency() != null) {
            com.chat99.server.wallet.WalletCurrency currency;
            try {
                currency = com.chat99.server.wallet.WalletCurrency.fromApiCode(req.communityCreatePriceCurrency());
            } catch (IllegalArgumentException e) {
                throw validation("community_create_price_currency is invalid");
            }
            if (currency == com.chat99.server.wallet.WalletCurrency.CNY) {
                throw validation("community_create_price_currency must be 99, USDT or TRX");
            }
            groupCreateLimitConfigService.setCommunityPriceCurrency(currency);
        }
        if (req.communityCreatePriceMinor() != null) {
            long v = req.communityCreatePriceMinor();
            if (v <= 0 || v > 1_000_000_000_000L) {
                throw validation("community_create_price_minor must be 1..1000000000000");
            }
            groupCreateLimitConfigService.setCommunityPriceMinor(v);
        }
        return getPushBusiness();
    }

    private List<WalletLimitItemView> listWalletLimits() {
        return walletLimitConfigRepository.findAll().stream()
            .filter(cfg -> cfg.getScene() == WalletLimitScene.TRANSFER
                || cfg.getScene() == WalletLimitScene.RED_PACKET
                || cfg.getScene() == WalletLimitScene.LIVE_TIP)
            .sorted(Comparator
                .comparing(WalletLimitConfig::getScene)
                .thenComparing(cfg -> cfg.getCurrency().name()))
            .map(this::toWalletLimitView)
            .toList();
    }

    private WalletLimitItemView toWalletLimitView(WalletLimitConfig cfg) {
        return new WalletLimitItemView(
            cfg.getId(),
            cfg.getScene().name(),
            cfg.getCurrency().getApiCode(),
            sceneLabel(cfg.getScene()),
            currencyLabel(cfg.getCurrency()),
            cfg.getPerTxMax(),
            cfg.getDailyMax(),
            cfg.isEnabled());
    }

    private void updateWalletLimit(UpdateWalletLimitItem item) {
        if (item.id() == null) {
            return;
        }
        WalletLimitConfig cfg = walletLimitConfigRepository.findById(item.id())
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "limit not found"));
        if (cfg.getScene() != WalletLimitScene.TRANSFER
            && cfg.getScene() != WalletLimitScene.RED_PACKET
            && cfg.getScene() != WalletLimitScene.LIVE_TIP) {
            throw validation("invalid wallet limit scene");
        }
        if (item.perTxMax() != null) {
            if (item.perTxMax() < 0) {
                throw validation("per_tx_max must be >= 0");
            }
            cfg.setPerTxMax(item.perTxMax());
        }
        if (item.dailyMax() != null) {
            if (item.dailyMax() < 0) {
                throw validation("daily_max must be >= 0");
            }
            cfg.setDailyMax(item.dailyMax());
        }
        if (item.enabled() != null) {
            cfg.setEnabled(item.enabled());
        }
        walletLimitConfigRepository.save(cfg);
    }

    private static String sceneLabel(WalletLimitScene scene) {
        return switch (scene) {
            case TRANSFER -> "转账";
            case RED_PACKET -> "红包";
            case WITHDRAW -> "提现";
            case EXCHANGE -> "闪兑";
            case LIVE_TIP -> "直播打赏";
        };
    }

    private static String currencyLabel(WalletCurrency currency) {
        return switch (currency) {
            case USDT -> "USDT";
            case PLATFORM, CNY -> "99币";
            case TRX -> "TRX";
        };
    }

    public InfrastructureConfigView getInfrastructure() {
        Map<String, String> all = appSettingService.snapshot();
        List<InfrastructureItem> items = new ArrayList<>();
        for (String key : INFRA_KEYS) {
            String value = all.get(key);
            items.add(new InfrastructureItem(
                key,
                INFRA_LABELS.getOrDefault(key, key),
                value != null && !value.isBlank(),
                mask(key, value),
                SECRET_KEYS.contains(key)));
        }
        return new InfrastructureConfigView(items);
    }

    public InfrastructureItem updateInfrastructure(String key, String value) {
        if (!INFRA_KEYS.contains(key)) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "unknown key");
        }
        if (value == null || value.isBlank()) {
            throw validation("value required");
        }
        String trimmed = value.trim();
        if (AppSettingService.JWT_SECRET.equals(key) && trimmed.length() < 32) {
            throw validation("JWT_SECRET must be at least 32 characters");
        }
        appSettingService.set(key, trimmed);
        if (key.startsWith("OSS_")) {
            ossClient.invalidate();
        }
        return new InfrastructureItem(
            key,
            INFRA_LABELS.getOrDefault(key, key),
            true,
            mask(key, trimmed),
            SECRET_KEYS.contains(key));
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static AdminApiException validation(String message) {
        return new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", message);
    }

    private static String maskSecret(String value) {
        return mask(null, value);
    }

    private static String mask(String key, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (key != null && !SECRET_KEYS.contains(key)) {
            return value;
        }
        if (key == null) {
            int n = value.length();
            if (n <= 6) {
                return "***";
            }
            return value.substring(0, 3) + "***" + value.substring(n - 3);
        }
        int n = value.length();
        if (n <= 6) {
            return "***";
        }
        return value.substring(0, 3) + "***" + value.substring(n - 3);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PlatformConfigView(
        String website,
        String email,
        String customerServiceUrl,
        String feedbackPrefix,
        int maxFeedbackScreenshots,
        int maxFeedbackContentLength) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdatePlatformRequest(
        String website,
        String email,
        String customerServiceUrl,
        String feedbackPrefix,
        Integer maxFeedbackScreenshots,
        Integer maxFeedbackContentLength) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PushBusinessConfigView(
        boolean pushEnabled,
        boolean skipWhenOnline,
        boolean voipPushEnabled,
        boolean jpushEnabled,
        String jpushAppKey,
        String jpushMasterSecret,
        String jpushBaseUrl,
        boolean imCallbackEnabled,
        boolean chatPushEnabled,
        String callbackToken,
        String allowedSdkAppIds,
        boolean chatPushSkipWhenOnline,
        String skipSenderIds,
        int maxGroupMembersPerPush,
        int dedupTtlHours,
        int payPinMaxFailures,
        int payPinLockMinutes,
        int redPacketExpireHours,
        long minDepositUsdtMicro,
        int depositConfirmations,
        String depositMode,
        boolean depositMnemonicConfigured,
        boolean hotWalletConfigured,
        String trongridApiKey,
        List<WalletLimitItemView> walletLimits,
        boolean groupCreateLimitEnabled,
        int groupJoinLimitMax,
        int groupJoinLimitMaxCommunity,
        int groupCreateLimitMaxCommunity,
        boolean groupCreateLimitEnforce,
        boolean groupCreateLimitLogOnly,
        boolean groupCreateLimitUseImCountFallback,
        String communityCreatePriceCurrency,
        long communityCreatePriceMinor) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WalletLimitItemView(
        long id,
        String scene,
        String currency,
        String sceneLabel,
        String currencyLabel,
        long perTxMax,
        long dailyMax,
        boolean enabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateWalletLimitItem(
        Long id,
        Long perTxMax,
        Long dailyMax,
        Boolean enabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdatePushBusinessRequest(
        Boolean pushEnabled,
        Boolean skipWhenOnline,
        Boolean voipPushEnabled,
        Boolean jpushEnabled,
        String jpushAppKey,
        String jpushMasterSecret,
        String jpushBaseUrl,
        Boolean imCallbackEnabled,
        Boolean chatPushEnabled,
        String callbackToken,
        String allowedSdkAppIds,
        Boolean chatPushSkipWhenOnline,
        String skipSenderIds,
        Integer maxGroupMembersPerPush,
        Integer dedupTtlHours,
        Integer payPinMaxFailures,
        Integer payPinLockMinutes,
        Integer redPacketExpireHours,
        Long minDepositUsdtMicro,
        Integer depositConfirmations,
        String depositMode,
        String trongridApiKey,
        List<UpdateWalletLimitItem> walletLimits,
        Boolean groupCreateLimitEnabled,
        Integer groupJoinLimitMax,
        Integer groupJoinLimitMaxCommunity,
        Integer groupCreateLimitMaxCommunity,
        Boolean groupCreateLimitEnforce,
        Boolean groupCreateLimitLogOnly,
        Boolean groupCreateLimitUseImCountFallback,
        String communityCreatePriceCurrency,
        Long communityCreatePriceMinor) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InfrastructureItem(
        String key,
        String label,
        boolean set,
        String preview,
        boolean secret) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InfrastructureConfigView(List<InfrastructureItem> items) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateInfrastructureRequest(String value) {}
}
