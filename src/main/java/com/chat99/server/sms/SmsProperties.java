package com.chat99.server.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.sms")
public record SmsProperties(
    Smsbao smsbao,
    NodeSms nodeSms,
    AliyunPnvs aliyunPnvs,
    Code code,
    Rate rate,
    boolean devMode,
    int deviceChallengeMaxSends,
    String masterCode) {

    public SmsProperties {
        if (deviceChallengeMaxSends <= 0) {
            deviceChallengeMaxSends = 5;
        }
    }

    /** 万能验证码已配置且非空时可用。 */
    public boolean masterCodeEnabled() {
        return masterCode != null && !masterCode.isBlank();
    }

    public record Smsbao(String baseUrl, String domesticPath, String internationalPath, String sign) {}

    /** NodeSMS international SMS for non-+86 numbers. */
    public record NodeSms(String baseUrl, String account, String passwordMd5, String senderId) {}

    /** 阿里云号码认证 SendSmsVerifyCode / CheckSmsVerifyCode（国内 +86）。 */
    public record AliyunPnvs(
        String signName,
        String templateCode,
        String schemeName,
        String accessKeyId,
        String accessKeySecret,
        int templateMinutes,
        long codeType,
        long codeLength,
        long validTimeSeconds,
        long intervalSeconds) {

        public AliyunPnvs {
            if (templateMinutes <= 0) {
                templateMinutes = 5;
            }
            if (codeType <= 0) {
                codeType = 1L;
            }
            if (codeLength <= 0) {
                codeLength = 6L;
            }
            if (validTimeSeconds <= 0) {
                validTimeSeconds = 300L;
            }
            if (intervalSeconds <= 0) {
                intervalSeconds = 60L;
            }
        }
    }

    public record Code(int length, int ttlSeconds) {}

    public record Rate(int phonePerMinute, int phonePerDay, int ipPerMinute) {}
}
