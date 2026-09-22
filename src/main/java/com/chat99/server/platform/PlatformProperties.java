package com.chat99.server.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.platform")
public record PlatformProperties(
    String website,
    String email,
    String version,
    String build,
    String downloadUrl,
    String feedbackPrefix,
    int maxFeedbackScreenshots,
    int maxFeedbackContentLength,
    String grayHashSecret
) {
    public PlatformProperties {
        if (website == null) website = "";
        if (email == null) email = "";
        if (version == null) version = "";
        if (build == null) build = "";
        if (downloadUrl == null) downloadUrl = "";
        if (feedbackPrefix == null || feedbackPrefix.isBlank()) {
            feedbackPrefix = "feedback/";
        } else if (!feedbackPrefix.endsWith("/")) {
            feedbackPrefix = feedbackPrefix + "/";
        }
        if (maxFeedbackScreenshots <= 0) maxFeedbackScreenshots = 5;
        if (maxFeedbackContentLength <= 0) maxFeedbackContentLength = 2000;
        if (grayHashSecret == null) grayHashSecret = "";
    }
}