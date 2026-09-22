package com.chat99.server.push;

import com.chat99.server.common.AppSettingRepository;
import com.chat99.server.im.ImCallbackProperties;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PushConfigService {

    private static final Logger log = LoggerFactory.getLogger(PushConfigService.class);

    private static final String KEY_PUSH_ENABLED = "push.enabled";
    private static final String KEY_PUSH_SKIP_WHEN_ONLINE = "push.skip_when_online";
    private static final String KEY_VOIP_PUSH_ENABLED = "push.voip_enabled";
    private static final String KEY_IM_CALLBACK_ENABLED = "im.callback.enabled";
    private static final String KEY_IM_CHAT_PUSH_ENABLED = "im.callback.chat_push_enabled";
    private static final String KEY_IM_CALLBACK_TOKEN = "im.callback.callback_token";
    private static final String KEY_IM_ALLOWED_SDK_APP_IDS = "im.callback.allowed_sdk_app_ids";
    private static final String KEY_IM_CHAT_PUSH_SKIP_WHEN_ONLINE = "im.callback.chat_push_skip_when_online";
    private static final String KEY_IM_SKIP_SENDER_IDS = "im.callback.skip_sender_ids";
    private static final String KEY_IM_MAX_GROUP_MEMBERS = "im.callback.max_group_members_per_push";
    private static final String KEY_IM_DEDUP_TTL_HOURS = "im.callback.dedup_ttl_hours";
    private static final String KEY_IM_GROUP_PUSH_AGG_SECONDS_SMALL = "im.callback.group_push_agg_seconds_small";
    private static final String KEY_IM_GROUP_PUSH_AGG_SECONDS_MEDIUM = "im.callback.group_push_agg_seconds_medium";
    private static final String KEY_IM_GROUP_PUSH_AGG_SECONDS_LARGE = "im.callback.group_push_agg_seconds_large";
    private static final String KEY_IM_GROUP_PUSH_SMALL_THRESHOLD = "im.callback.group_push_small_group_threshold";
    private static final String KEY_IM_GROUP_PUSH_LARGE_THRESHOLD = "im.callback.group_push_large_group_threshold";
    private static final String KEY_IM_GROUP_MEMBER_CACHE_TTL_MINUTES = "im.callback.group_member_cache_ttl_minutes";
    private static final String KEY_IM_GROUP_PUSH_FLUSH_BATCH_SIZE = "im.callback.group_push_flush_batch_size";
    private static final String KEY_JPUSH_ENABLED = "push.jpush_enabled";
    private static final String KEY_JPUSH_APP_KEY = "push.jpush_app_key";
    private static final String KEY_JPUSH_MASTER_SECRET = "push.jpush_master_secret";
    private static final String KEY_JPUSH_BASE_URL = "push.jpush_base_url";
    private static final String KEY_JPUSH_THIRD_PARTY_ENABLED = "push.jpush_third_party_enabled";
    private static final String KEY_JPUSH_HUAWEI_DISTRIBUTION = "push.jpush_huawei_distribution";
    private static final String KEY_JPUSH_HONOR_DISTRIBUTION = "push.jpush_honor_distribution";
    private static final String KEY_JPUSH_OPPO_DISTRIBUTION = "push.jpush_oppo_distribution";
    private static final String KEY_JPUSH_VIVO_DISTRIBUTION = "push.jpush_vivo_distribution";
    private static final String KEY_JPUSH_XIAOMI_DISTRIBUTION = "push.jpush_xiaomi_distribution";
    private static final String KEY_JPUSH_XIAOMI_CHANNEL_ID = "push.jpush_xiaomi_channel_id";

    private final AppSettingRepository appSettingRepository;
    private final PushProperties pushProperties;
    private final ImCallbackProperties imCallbackProperties;

    public PushConfigService(AppSettingRepository appSettingRepository,
                             PushProperties pushProperties,
                             ImCallbackProperties imCallbackProperties) {
        this.appSettingRepository = appSettingRepository;
        this.pushProperties = pushProperties;
        this.imCallbackProperties = imCallbackProperties;
    }

    public boolean isPushEnabled() {
        return getBooleanOrDefault(KEY_PUSH_ENABLED, pushProperties.enabled());
    }

    public boolean isSkipWhenOnline() {
        return getBooleanOrDefault(KEY_PUSH_SKIP_WHEN_ONLINE, pushProperties.skipWhenOnline());
    }

    public boolean isVoipPushEnabled() {
        return getBooleanOrDefault(KEY_VOIP_PUSH_ENABLED, true);
    }

    public boolean isImCallbackEnabled() {
        return getBooleanOrDefault(KEY_IM_CALLBACK_ENABLED, imCallbackProperties.enabled());
    }

    public boolean isChatPushEnabled() {
        return getBooleanOrDefault(KEY_IM_CHAT_PUSH_ENABLED, imCallbackProperties.chatPushEnabled());
    }

    public String getCallbackToken() {
        return getOrDefault(KEY_IM_CALLBACK_TOKEN, imCallbackProperties.callbackToken());
    }

    public String getAllowedSdkAppIds() {
        return getOrDefault(KEY_IM_ALLOWED_SDK_APP_IDS, imCallbackProperties.allowedSdkAppIds());
    }

    public boolean isChatPushSkipWhenOnline() {
        return getBooleanOrDefault(KEY_IM_CHAT_PUSH_SKIP_WHEN_ONLINE, imCallbackProperties.chatPushSkipWhenOnline());
    }

    public List<String> getSkipSenderIds() {
        String csv = getOrDefault(KEY_IM_SKIP_SENDER_IDS, joinList(imCallbackProperties.skipSenderIds()));
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    public int getMaxGroupMembersPerPush() {
        return getIntOrDefault(KEY_IM_MAX_GROUP_MEMBERS, imCallbackProperties.maxGroupMembersPerPush());
    }

    public int getDedupTtlHours() {
        return getIntOrDefault(KEY_IM_DEDUP_TTL_HOURS, imCallbackProperties.dedupTtlHours());
    }

    public int getGroupPushAggSecondsSmall() {
        return getIntOrDefault(KEY_IM_GROUP_PUSH_AGG_SECONDS_SMALL, imCallbackProperties.groupPushAggSecondsSmall());
    }

    public int getGroupPushAggSecondsMedium() {
        return getIntOrDefault(KEY_IM_GROUP_PUSH_AGG_SECONDS_MEDIUM, imCallbackProperties.groupPushAggSecondsMedium());
    }

    public int getGroupPushAggSecondsLarge() {
        return getIntOrDefault(KEY_IM_GROUP_PUSH_AGG_SECONDS_LARGE, imCallbackProperties.groupPushAggSecondsLarge());
    }

    public int getGroupPushSmallGroupThreshold() {
        return getIntOrDefault(KEY_IM_GROUP_PUSH_SMALL_THRESHOLD, imCallbackProperties.groupPushSmallGroupThreshold());
    }

    public int getGroupPushLargeGroupThreshold() {
        return getIntOrDefault(KEY_IM_GROUP_PUSH_LARGE_THRESHOLD, imCallbackProperties.groupPushLargeGroupThreshold());
    }

    public int getGroupMemberCacheTtlMinutes() {
        return getIntOrDefault(KEY_IM_GROUP_MEMBER_CACHE_TTL_MINUTES, imCallbackProperties.groupMemberCacheTtlMinutes());
    }

    public int getGroupPushFlushBatchSize() {
        return getIntOrDefault(KEY_IM_GROUP_PUSH_FLUSH_BATCH_SIZE, imCallbackProperties.groupPushFlushBatchSize());
    }

    public boolean isJpushEnabled() {
        return getBooleanOrDefault(KEY_JPUSH_ENABLED, pushProperties.jpush().enabled());
    }

    public String getJpushAppKey() {
        return getOrDefault(KEY_JPUSH_APP_KEY, pushProperties.jpush().appKey());
    }

    public String getJpushMasterSecret() {
        return getOrDefault(KEY_JPUSH_MASTER_SECRET, pushProperties.jpush().masterSecret());
    }

    public String getJpushBaseUrl() {
        String fromDb = getOrDefault(KEY_JPUSH_BASE_URL, null);
        if (fromDb != null && !fromDb.isBlank()) {
            return fromDb.trim();
        }
        String fromProps = pushProperties.jpush().baseUrl();
        return (fromProps == null || fromProps.isBlank()) ? "https://api.jpush.cn" : fromProps.trim();
    }

    public boolean isJpushThirdPartyEnabled() {
        return getBooleanOrDefault(KEY_JPUSH_THIRD_PARTY_ENABLED, pushProperties.jpush().thirdPartyEnabled());
    }

    public String getJpushHuaweiDistribution() {
        return getOrDefault(KEY_JPUSH_HUAWEI_DISTRIBUTION, pushProperties.jpush().huaweiDistribution());
    }

    public String getJpushHonorDistribution() {
        return getOrDefault(KEY_JPUSH_HONOR_DISTRIBUTION, pushProperties.jpush().honorDistribution());
    }

    public String getJpushOppoDistribution() {
        return getOrDefault(KEY_JPUSH_OPPO_DISTRIBUTION, pushProperties.jpush().oppoDistribution());
    }

    public String getJpushVivoDistribution() {
        return getOrDefault(KEY_JPUSH_VIVO_DISTRIBUTION, pushProperties.jpush().vivoDistribution());
    }

    public String getJpushXiaomiDistribution() {
        return getOrDefault(KEY_JPUSH_XIAOMI_DISTRIBUTION, pushProperties.jpush().xiaomiDistribution());
    }

    public String getJpushXiaomiChannelId() {
        return getOrDefault(KEY_JPUSH_XIAOMI_CHANNEL_ID, pushProperties.jpush().xiaomiChannelId());
    }

    public JpushThirdPartyConfig getJpushThirdPartyConfig() {
        return new JpushThirdPartyConfig(
            isJpushThirdPartyEnabled(),
            getJpushHuaweiDistribution(),
            getJpushHonorDistribution(),
            getJpushOppoDistribution(),
            getJpushVivoDistribution(),
            getJpushXiaomiDistribution(),
            getJpushXiaomiChannelId());
    }

    public void setPushEnabled(boolean value) {
        setValue(KEY_PUSH_ENABLED, Boolean.toString(value));
    }

    public void setSkipWhenOnline(boolean value) {
        setValue(KEY_PUSH_SKIP_WHEN_ONLINE, Boolean.toString(value));
    }

    public void setVoipPushEnabled(boolean value) {
        setValue(KEY_VOIP_PUSH_ENABLED, Boolean.toString(value));
    }

    public void setImCallbackEnabled(boolean value) {
        setValue(KEY_IM_CALLBACK_ENABLED, Boolean.toString(value));
    }

    public void setChatPushEnabled(boolean value) {
        setValue(KEY_IM_CHAT_PUSH_ENABLED, Boolean.toString(value));
    }

    public void setCallbackToken(String value) {
        setValue(KEY_IM_CALLBACK_TOKEN, value);
    }

    public void setAllowedSdkAppIds(String value) {
        setValue(KEY_IM_ALLOWED_SDK_APP_IDS, value);
    }

    public void setChatPushSkipWhenOnline(boolean value) {
        setValue(KEY_IM_CHAT_PUSH_SKIP_WHEN_ONLINE, Boolean.toString(value));
    }

    public void setSkipSenderIds(String csv) {
        setValue(KEY_IM_SKIP_SENDER_IDS, csv);
    }

    public void setMaxGroupMembersPerPush(int value) {
        setValue(KEY_IM_MAX_GROUP_MEMBERS, String.valueOf(value));
    }

    public void setDedupTtlHours(int value) {
        setValue(KEY_IM_DEDUP_TTL_HOURS, String.valueOf(value));
    }

    public void setJpushEnabled(boolean value) {
        setValue(KEY_JPUSH_ENABLED, Boolean.toString(value));
    }

    public void setJpushAppKey(String value) {
        setValue(KEY_JPUSH_APP_KEY, value);
    }

    public void setJpushMasterSecret(String value) {
        setValue(KEY_JPUSH_MASTER_SECRET, value);
    }

    public void setJpushBaseUrl(String value) {
        setValue(KEY_JPUSH_BASE_URL, value);
    }

    public void setJpushThirdPartyEnabled(boolean value) {
        setValue(KEY_JPUSH_THIRD_PARTY_ENABLED, Boolean.toString(value));
    }

    public void setJpushHuaweiDistribution(String value) {
        setValue(KEY_JPUSH_HUAWEI_DISTRIBUTION, value);
    }

    public void setJpushHonorDistribution(String value) {
        setValue(KEY_JPUSH_HONOR_DISTRIBUTION, value);
    }

    public void setJpushOppoDistribution(String value) {
        setValue(KEY_JPUSH_OPPO_DISTRIBUTION, value);
    }

    public void setJpushVivoDistribution(String value) {
        setValue(KEY_JPUSH_VIVO_DISTRIBUTION, value);
    }

    public void setJpushXiaomiDistribution(String value) {
        setValue(KEY_JPUSH_XIAOMI_DISTRIBUTION, value);
    }

    public void setJpushXiaomiChannelId(String value) {
        setValue(KEY_JPUSH_XIAOMI_CHANNEL_ID, value);
    }

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
        if (value == null) {
            appSettingRepository.deleteById(key);
        } else {
            appSettingRepository.findById(key).ifPresentOrElse(
                setting -> {
                    setting.setValue(value);
                    appSettingRepository.save(setting);
                },
                () -> appSettingRepository.save(new com.chat99.server.common.AppSetting(key, value))
            );
        }
        boolean sensitive = KEY_IM_CALLBACK_TOKEN.equals(key)
            || KEY_JPUSH_APP_KEY.equals(key)
            || KEY_JPUSH_MASTER_SECRET.equals(key);
        log.info("Push config updated: {} = [{}]", key, sensitive ? "***" : value);
    }

    private static String joinList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return String.join(",", items);
    }
}
