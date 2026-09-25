package com.chat99.server.group;

import com.chat99.server.common.AppSettingRepository;
import com.chat99.server.wallet.WalletCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GroupCreateLimitConfigService {

    private static final Logger log = LoggerFactory.getLogger(GroupCreateLimitConfigService.class);

    static final String KEY_ENABLED = "group.create_limit.enabled";
    /** @deprecated 不再用于业务；保留 key 兼容旧数据 */
    static final String KEY_MAX_WORK = "group.create_limit.max_work";
    /** @deprecated 兼容旧配置 */
    static final String KEY_MAX_PUBLIC_LEGACY = "group.create_limit.max_public";
    static final String KEY_MAX_COMMUNITY = "group.create_limit.max_community";
    static final String KEY_MAX_JOIN = "group.join_limit.max";
    static final String KEY_MAX_COMMUNITY_JOIN = "group.join_limit.max_community";
    static final String KEY_ENFORCE = "group.create_limit.enforce";
    static final String KEY_LOG_ONLY = "group.create_limit.log_only";
    static final String KEY_USE_IM_FALLBACK = "group.create_limit.use_im_count_fallback";
    static final String KEY_COMMUNITY_PRICE_CURRENCY = "group.community_create.price_currency";
    static final String KEY_COMMUNITY_PRICE_MINOR = "group.community_create.price_minor";
    private static final long DEFAULT_COMMUNITY_PRICE_MINOR = 1_000_000L;

    private final AppSettingRepository appSettingRepository;
    private final GroupCreateLimitProperties defaults;

    public GroupCreateLimitConfigService(AppSettingRepository appSettingRepository,
                                        GroupCreateLimitProperties defaults) {
        this.appSettingRepository = appSettingRepository;
        this.defaults = defaults;
    }

    public boolean isEnabled() {
        return getBooleanOrDefault(KEY_ENABLED, defaults.enabled());
    }

    public boolean isEnforce() {
        return getBooleanOrDefault(KEY_ENFORCE, defaults.enforce());
    }

    public boolean isLogOnly() {
        return getBooleanOrDefault(KEY_LOG_ONLY, defaults.logOnly());
    }

    public boolean isUseImCountFallback() {
        return getBooleanOrDefault(KEY_USE_IM_FALLBACK, defaults.useImCountFallback());
    }

    public WalletCurrency getCommunityPriceCurrency() {
        try {
            WalletCurrency currency = WalletCurrency.fromApiCode(
                getOrDefault(KEY_COMMUNITY_PRICE_CURRENCY, "99"));
            return currency == WalletCurrency.CNY ? WalletCurrency.PLATFORM : currency;
        } catch (IllegalArgumentException ignored) {
            return WalletCurrency.PLATFORM;
        }
    }

    public long getCommunityPriceMinor() {
        try {
            long value = Long.parseLong(getOrDefault(KEY_COMMUNITY_PRICE_MINOR,
                Long.toString(DEFAULT_COMMUNITY_PRICE_MINOR)));
            return value > 0 && value <= 1_000_000_000_000L
                ? value : DEFAULT_COMMUNITY_PRICE_MINOR;
        } catch (NumberFormatException ignored) {
            return DEFAULT_COMMUNITY_PRICE_MINOR;
        }
    }

    public void setCommunityPriceCurrency(WalletCurrency currency) {
        setValue(KEY_COMMUNITY_PRICE_CURRENCY, currency.getApiCode());
    }

    public void setCommunityPriceMinor(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("community price must be positive");
        setValue(KEY_COMMUNITY_PRICE_MINOR, Long.toString(amount));
    }

    /** @deprecated Work 建群数已取消 */
    public int getMaxWorkGroups() {
        if (appSettingRepository.findById(KEY_MAX_WORK).isPresent()) {
            return getIntOrDefault(KEY_MAX_WORK, defaults.maxWorkGroups());
        }
        return getIntOrDefault(KEY_MAX_PUBLIC_LEGACY, defaults.maxWorkGroups());
    }

    public int getMaxCommunityGroups() {
        return getIntOrDefault(KEY_MAX_COMMUNITY, defaults.maxCommunityGroups());
    }

    public int getMaxJoinGroups() {
        return getIntOrDefault(KEY_MAX_JOIN, defaults.maxJoinGroups());
    }

    public int getMaxCommunityJoinGroups() {
        return getIntOrDefault(KEY_MAX_COMMUNITY_JOIN, defaults.maxCommunityJoinGroups());
    }

    /** 建群数量不限制；Community 只靠付费，不再按人卡上限。 */
    public int limitForType(String groupType) {
        return -1;
    }

    /**
     * 加入数量上限：Community → 社群加入桶；其它类型 → 非社群加入桶。
     * 返回 -1 表示不限制。
     */
    public int joinLimitForGroupType(String groupType) {
        if (isCommunity(groupType)) {
            return getMaxCommunityJoinGroups();
        }
        return getMaxJoinGroups();
    }

    public static boolean isCommunity(String groupType) {
        return groupType != null && "Community".equalsIgnoreCase(groupType.trim());
    }

    public GroupCreateLimitAdminView snapshot() {
        return new GroupCreateLimitAdminView(
            isEnabled(),
            getMaxJoinGroups(),
            getMaxCommunityJoinGroups(),
            getMaxCommunityGroups(),
            isEnforce(),
            isLogOnly(),
            isUseImCountFallback());
    }

    public void setEnabled(boolean value) {
        setValue(KEY_ENABLED, Boolean.toString(value));
    }

    /** @deprecated */
    public void setMaxWorkGroups(int value) {
        setValue(KEY_MAX_WORK, String.valueOf(value));
    }

    public void setMaxCommunityGroups(int value) {
        setValue(KEY_MAX_COMMUNITY, String.valueOf(value));
    }

    public void setMaxJoinGroups(int value) {
        setValue(KEY_MAX_JOIN, String.valueOf(value));
    }

    public void setMaxCommunityJoinGroups(int value) {
        setValue(KEY_MAX_COMMUNITY_JOIN, String.valueOf(value));
    }

    public void setEnforce(boolean value) {
        setValue(KEY_ENFORCE, Boolean.toString(value));
    }

    public void setLogOnly(boolean value) {
        setValue(KEY_LOG_ONLY, Boolean.toString(value));
    }

    public void setUseImCountFallback(boolean value) {
        setValue(KEY_USE_IM_FALLBACK, Boolean.toString(value));
    }

    public record GroupCreateLimitAdminView(
        boolean enabled,
        int maxJoinGroups,
        int maxCommunityJoinGroups,
        int maxCommunityGroups,
        boolean enforce,
        boolean logOnly,
        boolean useImCountFallback) {}

    private String getOrDefault(String key, String defaultValue) {
        return appSettingRepository.findById(key)
            .map(setting -> {
                String val = setting.getValue();
                return val != null ? val : defaultValue;
            })
            .orElse(defaultValue);
    }

    private boolean getBooleanOrDefault(String key, boolean defaultValue) {
        String val = getOrDefault(key, null);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(val.trim()) || "1".equals(val.trim());
    }

    private int getIntOrDefault(String key, int defaultValue) {
        String val = getOrDefault(key, null);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void setValue(String key, String value) {
        appSettingRepository.findById(key).ifPresentOrElse(
            setting -> {
                setting.setValue(value);
                appSettingRepository.save(setting);
            },
            () -> appSettingRepository.save(new com.chat99.server.common.AppSetting(key, value))
        );
        log.info("Group create/join limit config updated: {} = {}", key, value);
    }
}
