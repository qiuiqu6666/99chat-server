package com.chat99.server.sms;

import com.chat99.server.common.AppSettingService;
import com.chat99.server.common.PhoneUtils;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SmsbaoClient {

    private static final Logger log = LoggerFactory.getLogger(SmsbaoClient.class);

    private final SmsProperties props;
    private final AppSettingService settings;
    private final PhoneUtils phoneUtils;
    private final OkHttpClient http;

    public SmsbaoClient(SmsProperties props, AppSettingService settings, PhoneUtils phoneUtils) {
        this.props = props;
        this.settings = settings;
        this.phoneUtils = phoneUtils;
        this.http = new OkHttpClient();
    }

    public void send(String phoneE164, String countryCode, String content) {
        SmsProperties.Smsbao s = props.smsbao();
        boolean domestic = phoneUtils.isDomestic(countryCode);
        if (props.devMode()) {
            log.info("[SMS-DEV] phone={} domestic={} content={}",
                phoneUtils.mask(phoneE164), domestic, content);
            return;
        }
        String username = settings.get(AppSettingService.SMSBAO_USER).orElse(null);
        String passwordMd5 = settings.get(AppSettingService.SMSBAO_PWD_MD5).orElse(null);
        if (username == null || passwordMd5 == null) {
            log.warn("smsbao credentials missing in app_setting; SMS not sent");
            throw new SmsSendException("SMS_PROVIDER_NOT_CONFIGURED");
        }
        String path = domestic ? s.domesticPath() : s.internationalPath();
        String number = domestic ? phoneE164.substring(1 + countryCode.length()) : phoneE164.substring(1);
        String text = domestic ? (s.sign() + content) : content;
        String url = s.baseUrl() + path
            + "?u=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "&p=" + URLEncoder.encode(passwordMd5, StandardCharsets.UTF_8)
            + "&m=" + URLEncoder.encode(number, StandardCharsets.UTF_8)
            + "&c=" + URLEncoder.encode(text, StandardCharsets.UTF_8);

        Request request = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(request).execute()) {
            ResponseBody body = resp.body();
            String code = body != null ? body.string().trim() : "";
            if (!"0".equals(code)) {
                log.warn("smsbao send failed phone={} code={}", phoneUtils.mask(phoneE164), code);
                throw new SmsSendException("SMS_PROVIDER_FAILED:" + code);
            }
            log.info("smsbao send ok phone={} domestic={}", phoneUtils.mask(phoneE164), domestic);
        } catch (IOException e) {
            log.warn("smsbao io error phone={} err={}", phoneUtils.mask(phoneE164), e.getMessage());
            throw new SmsSendException("SMS_PROVIDER_IO");
        }
    }

    public static class SmsSendException extends RuntimeException {
        public SmsSendException(String m) { super(m); }
    }
}
