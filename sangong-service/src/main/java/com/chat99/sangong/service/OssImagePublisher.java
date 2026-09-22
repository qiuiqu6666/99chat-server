package com.chat99.sangong.service;

import com.chat99.sangong.config.SangongProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 报表图片经主服务 Integration API 上传到 OSS（不落本地磁盘）。
 * OSS 凭据只保存在主服务；对象由主服务定时任务按保留期清理。
 */
@Service
public class OssImagePublisher {
    private static final Logger log = LoggerFactory.getLogger(OssImagePublisher.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final SangongProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OssImagePublisher(SangongProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    }

    /** 上传 JPG 字节，成功返回公网 URL，失败返回 null。 */
    @SuppressWarnings("unchecked")
    public String uploadJpeg(byte[] bytes, String fileName) {
        String baseUrl = props.getIntegration().getBaseUrl();
        String token = props.getIntegration().getApiToken();
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
            log.warn("oss upload skip: integration not configured");
            return null;
        }
        String url = baseUrl.replaceAll("/+$", "") + "/integration/v1/oss/report-images";
        try {
            Map<String, Object> body = Map.of(
                "fileName", fileName,
                "contentType", "image/jpeg",
                "dataBase64", Base64.getEncoder().encodeToString(bytes));
            Request request = new Request.Builder()
                .url(url)
                .header("X-Integration-Token", token)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .build();
            try (Response response = http.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful() || raw.isBlank()) {
                    log.warn("oss upload failed status={} body={}", response.code(),
                        raw.length() > 200 ? raw.substring(0, 200) : raw);
                    return null;
                }
                Map<String, Object> payload = mapper.readValue(raw, Map.class);
                // 主服务全局响应包装 {code,message,data:{...}}
                if (payload.get("data") instanceof Map<?, ?> data) {
                    payload = (Map<String, Object>) data;
                }
                Object result = payload.get("url");
                String publicUrl = result == null ? "" : result.toString().trim();
                if (publicUrl.isEmpty()) {
                    log.warn("oss upload missing url in response");
                    return null;
                }
                return publicUrl;
            }
        } catch (Exception e) {
            log.warn("oss upload exception: {}", e.getMessage());
            return null;
        }
    }
}
