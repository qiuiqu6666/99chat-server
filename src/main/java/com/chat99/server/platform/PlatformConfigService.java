package com.chat99.server.platform;

import com.chat99.server.common.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlatformConfigService {

    private static final Logger log = LoggerFactory.getLogger(PlatformConfigService.class);

    private static final String KEY_WEBSITE = "platform.website";
    private static final String KEY_EMAIL = "platform.email";
    private static final String KEY_VERSION = "platform.version";
    private static final String KEY_BUILD = "platform.build";
    private static final String KEY_DOWNLOAD_URL = "platform.download_url";
    private static final String KEY_FEEDBACK_PREFIX = "platform.feedback_prefix";
    private static final String KEY_MAX_FEEDBACK_SCREENSHOTS = "platform.max_feedback_screenshots";
    private static final String KEY_MAX_FEEDBACK_CONTENT_LENGTH = "platform.max_feedback_content_length";
    private static final String KEY_CUSTOMER_SERVICE_URL = "platform.customer_service_url";

    private final AppSettingRepository appSettingRepository;
    private final PlatformProperties props;

    public PlatformConfigService(AppSettingRepository appSettingRepository, PlatformProperties props) {
        this.appSettingRepository = appSettingRepository;
        this.props = props;
    }

    public String getWebsite() {
        return getOrDefault(KEY_WEBSITE, props.website());
    }

    public String getEmail() {
        return getOrDefault(KEY_EMAIL, props.email());
    }

    public String getVersion() {
        return getOrDefault(KEY_VERSION, props.version());
    }

    public String getBuild() {
        return getOrDefault(KEY_BUILD, props.build());
    }

    public String getDownloadUrl() {
        return getOrDefault(KEY_DOWNLOAD_URL, props.downloadUrl());
    }

    public String getFeedbackPrefix() {
        return getOrDefault(KEY_FEEDBACK_PREFIX, props.feedbackPrefix());
    }

    public int getMaxFeedbackScreenshots() {
        return getIntOrDefault(KEY_MAX_FEEDBACK_SCREENSHOTS, props.maxFeedbackScreenshots());
    }

    public int getMaxFeedbackContentLength() {
        return getIntOrDefault(KEY_MAX_FEEDBACK_CONTENT_LENGTH, props.maxFeedbackContentLength());
    }

    public String getCustomerServiceUrl() {
        return getOrDefault(KEY_CUSTOMER_SERVICE_URL, "");
    }

    public void setWebsite(String value) {
        setValue(KEY_WEBSITE, value);
    }

    public void setEmail(String value) {
        setValue(KEY_EMAIL, value);
    }

    public void setVersion(String value) {
        setValue(KEY_VERSION, value);
    }

    public void setBuild(String value) {
        setValue(KEY_BUILD, value);
    }

    public void setDownloadUrl(String value) {
        setValue(KEY_DOWNLOAD_URL, value);
    }

    public void setFeedbackPrefix(String value) {
        setValue(KEY_FEEDBACK_PREFIX, value);
    }

    public void setMaxFeedbackScreenshots(int value) {
        setValue(KEY_MAX_FEEDBACK_SCREENSHOTS, String.valueOf(value));
    }

    public void setMaxFeedbackContentLength(int value) {
        setValue(KEY_MAX_FEEDBACK_CONTENT_LENGTH, String.valueOf(value));
    }

    public void setCustomerServiceUrl(String value) {
        setValue(KEY_CUSTOMER_SERVICE_URL, value);
    }

    private String getOrDefault(String key, String defaultValue) {
        return appSettingRepository.findById(key)
            .map(setting -> {
                String val = setting.getValue();
                return (val != null && !val.isBlank()) ? val : defaultValue;
            })
            .orElse(defaultValue);
    }

    private int getIntOrDefault(String key, int defaultValue) {
        String val = getOrDefault(key, null);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
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
                () -> {
                    com.chat99.server.common.AppSetting setting = new com.chat99.server.common.AppSetting(key, value);
                    appSettingRepository.save(setting);
                }
            );
        }
        log.info("Platform config updated: {} = [{}]", key, value != null ? "***" : "null");
    }
}
