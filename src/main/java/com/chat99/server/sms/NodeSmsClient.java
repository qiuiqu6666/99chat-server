package com.chat99.server.sms;

import com.chat99.server.common.PhoneUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NodeSmsClient {

    private static final Logger log = LoggerFactory.getLogger(NodeSmsClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final SmsProperties props;
    private final PhoneUtils phoneUtils;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http = new OkHttpClient();

    public NodeSmsClient(SmsProperties props, PhoneUtils phoneUtils, ObjectMapper objectMapper) {
        this.props = props;
        this.phoneUtils = phoneUtils;
        this.objectMapper = objectMapper;
    }

    public void send(String phoneE164, String countryCode, String content) {
        if (phoneUtils.isDomestic(countryCode)) {
            throw new IllegalArgumentException("NodeSMS only supports international numbers");
        }
        if (props.devMode()) {
            log.info("[SMS-DEV] provider=nodesms phone={} content={}", phoneUtils.mask(phoneE164), content);
            return;
        }

        SmsProperties.NodeSms cfg = props.nodeSms();
        if (cfg == null || blank(cfg.baseUrl()) || blank(cfg.account()) || blank(cfg.passwordMd5())) {
            log.warn("NodeSMS credentials missing; SMS not sent");
            throw new SmsbaoClient.SmsSendException("SMS_PROVIDER_NOT_CONFIGURED");
        }

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("account", cfg.account());
        payload.put("password", cfg.passwordMd5());
        payload.put("mobiles", phoneE164.substring(1));
        payload.put("content", content);
        if (!blank(cfg.senderId())) {
            payload.put("senderId", cfg.senderId());
        }
        String region = phoneUtils.regionForCountryCode(countryCode);
        if (!region.isBlank()) {
            payload.put("shortCode", region);
        }

        try {
            RequestBody body = RequestBody.create(objectMapper.writeValueAsString(payload), JSON);
            Request request = new Request.Builder().url(cfg.baseUrl()).post(body).build();
            try (Response response = http.newCall(request).execute()) {
                ResponseBody responseBody = response.body();
                String raw = responseBody == null ? "" : responseBody.string();
                JsonNode result = objectMapper.readTree(raw);
                String code = result.path("code").asText();
                if (!response.isSuccessful() || !"0".equals(code)) {
                    log.warn("NodeSMS send failed phone={} http={} code={}",
                        phoneUtils.mask(phoneE164), response.code(), code);
                    throw new SmsbaoClient.SmsSendException(
                        "SMS_PROVIDER_FAILED:" + (code.isBlank() ? response.code() : code));
                }
                log.info("NodeSMS send ok phone={} country={}", phoneUtils.mask(phoneE164), countryCode);
            }
        } catch (SmsbaoClient.SmsSendException e) {
            throw e;
        } catch (IOException e) {
            log.warn("NodeSMS I/O error phone={} err={}", phoneUtils.mask(phoneE164), e.getMessage());
            throw new SmsbaoClient.SmsSendException("SMS_PROVIDER_IO");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
