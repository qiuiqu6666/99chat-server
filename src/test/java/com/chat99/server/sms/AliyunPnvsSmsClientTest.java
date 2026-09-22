package com.chat99.server.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AliyunPnvsSmsClientTest {

    @Test
    void businessFailure_matchesProductionVerifyFailedMessage() {
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure(
            null,
            "code: 400, 验证失败 request id: 019FF62E-1E72-5A36-9BCB-1BBD0FD3A7FD"))
            .isTrue();
    }

    @Test
    void businessFailure_matchesChineseVariants() {
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure(null, "校验失败")).isTrue();
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure(null, "验证码错误")).isTrue();
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure(null, "验证码已过期")).isTrue();
    }

    @Test
    void businessFailure_matchesKnownCodes() {
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure("FAIL", null)).isTrue();
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure("VERIFY_CODE_ERROR", "oops")).isTrue();
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure("INVALID_VERIFY_CODE", "")).isTrue();
    }

    @Test
    void channelFailure_accessKeyNotTreatedAsBusiness() {
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure(
            "InvalidAccessKeyId",
            "Specified access key is not found"))
            .isFalse();
    }

    @Test
    void channelFailure_timeoutNotTreatedAsBusiness() {
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure(
            null,
            "connect timed out"))
            .isFalse();
    }

    @Test
    void emptyIsNotBusinessFailure() {
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure(null, null)).isFalse();
        assertThat(AliyunPnvsSmsClient.isVerifyCodeBusinessFailure("", "  ")).isFalse();
    }

    @Test
    void allowedTemplate_only100001() {
        assertThat(AliyunPnvsSmsClient.isAllowedTemplate("100001")).isTrue();
        assertThat(AliyunPnvsSmsClient.isAllowedTemplate(" 100001 ")).isTrue();
        assertThat(AliyunPnvsSmsClient.isAllowedTemplate("SMS_335270240")).isFalse();
        assertThat(AliyunPnvsSmsClient.isAllowedTemplate(null)).isFalse();
        assertThat(AliyunPnvsSmsClient.isAllowedTemplate("")).isFalse();
    }

    @Test
    void templateParam_for100001_includesCodeAndMin() throws Exception {
        String json = AliyunPnvsSmsClient.buildTemplateParamJson(
            "##code##", 5, new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(json).contains("\"code\"");
        assertThat(json).contains("##code##");
        assertThat(json).contains("\"min\"");
        assertThat(json).contains("5");
    }
}
