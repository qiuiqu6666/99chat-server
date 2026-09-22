package com.chat99.server.integration;

import com.aliyun.oss.model.PartETag;
import com.chat99.server.oss.OssClient;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 外部集成：报表图片上传到 OSS，供 sangong 等下游服务使用（不落本地磁盘）。
 * 对象统一放在 {@code sangong/bet-reports/yyyyMMdd/} 前缀下，由定时任务按天清理。
 */
@RestController
@RequestMapping("/integration/v1/oss")
public class IntegrationReportImageController {
    private static final Logger log = LoggerFactory.getLogger(IntegrationReportImageController.class);

    /** base64 解码后最大 10MB，报表 JPG 远小于此 */
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    private final IntegrationAuthService authService;
    private final OssClient ossClient;

    public IntegrationReportImageController(IntegrationAuthService authService, OssClient ossClient) {
        this.authService = authService;
        this.ossClient = ossClient;
    }

    public record UploadRequest(String fileName, String contentType, String dataBase64) {}

    public record PresignRequest(String fileName, String contentType) {}

    public record MultipartInitRequest(String fileName, String contentType, Long sizeBytes) {}

    public record MultipartPart(int partNumber, String etag) {}

    public record MultipartCompleteRequest(String objectKey, String uploadId, List<MultipartPart> parts) {}

    public record MultipartAbortRequest(String objectKey, String uploadId) {}

    static final int PART_SIZE = 2 * 1024 * 1024;
    static final int PART_URL_TTL = 900;
    static final int MAX_MULTIPART_BYTES = 64 * 1024 * 1024;

    @PostMapping("/report-images/presign")
    public Map<String, Object> presign(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) PresignRequest body) {
        authService.verify(headerToken, sign, requestTime);
        if (!ossClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }
        String fileName = body == null ? null : body.fileName();
        String contentType = body == null || body.contentType() == null || body.contentType().isBlank()
            ? "image/jpeg" : body.contentType().trim();
        String objectKey = SangongReportImageStorage.buildObjectKey(fileName);
        String uploadUrl = ossClient.presignedPutUrl(objectKey, contentType, 300);
        String publicUrl = ossClient.objectUrl(objectKey);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("uploadUrl", uploadUrl);
        out.put("publicUrl", publicUrl);
        out.put("objectKey", objectKey);
        return out;
    }

    @PostMapping("/report-images/multipart/init")
    public Map<String, Object> multipartInit(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) MultipartInitRequest body) {
        authService.verify(headerToken, sign, requestTime);
        if (!ossClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }
        long size = body == null || body.sizeBytes() == null ? 0 : body.sizeBytes();
        if (size <= 0 || size > MAX_MULTIPART_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SIZE");
        }
        String fileName = body.fileName();
        String contentType = body.contentType() == null || body.contentType().isBlank()
            ? "image/jpeg" : body.contentType().trim();
        String objectKey = SangongReportImageStorage.buildObjectKey(fileName);
        String uploadId = ossClient.initiateMultipart(objectKey, contentType);
        int partCount = (int) ((size + PART_SIZE - 1) / PART_SIZE);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int i = 1; i <= partCount; i++) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("partNumber", i);
            part.put("uploadUrl", ossClient.presignedPartPutUrl(objectKey, uploadId, i, PART_URL_TTL));
            parts.add(part);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("objectKey", objectKey);
        out.put("uploadId", uploadId);
        out.put("publicUrl", ossClient.objectUrl(objectKey));
        out.put("partSize", PART_SIZE);
        out.put("partContentType", OssClient.PART_PUT_CONTENT_TYPE);
        out.put("parts", parts);
        return out;
    }

    @PostMapping("/report-images/multipart/complete")
    public Map<String, Object> multipartComplete(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) MultipartCompleteRequest body) {
        authService.verify(headerToken, sign, requestTime);
        if (!ossClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }
        if (body == null || body.objectKey() == null || body.uploadId() == null || body.parts() == null
            || body.parts().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String objectKey = body.objectKey().trim();
        if (!objectKey.startsWith(SangongReportImageStorage.PREFIX) || objectKey.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_OBJECT_KEY");
        }
        int expected = body.parts().size();
        List<OssClient.UploadedPart> listed = ossClient.listMultipartParts(objectKey, body.uploadId().trim());
        if (listed.size() != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PART_COUNT_MISMATCH");
        }
        List<PartETag> tags = new ArrayList<>();
        long totalBytes = 0;
        for (int i = 0; i < listed.size(); i++) {
            OssClient.UploadedPart part = listed.get(i);
            if (part.partNumber() != i + 1 || part.etag() == null || part.etag().isBlank() || part.sizeBytes() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PARTS");
            }
            if (i < listed.size() - 1 && part.sizeBytes() < 100 * 1024) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PART_TOO_SMALL");
            }
            totalBytes += part.sizeBytes();
            tags.add(new PartETag(part.partNumber(), part.etag()));
        }
        ossClient.completeMultipart(objectKey, body.uploadId().trim(), tags);
        long stored = ossClient.objectContentLength(objectKey);
        if (stored != totalBytes) {
            log.warn("oss multipart size mismatch key={} partsBytes={} stored={}", objectKey, totalBytes, stored);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("publicUrl", ossClient.objectUrl(objectKey));
        out.put("objectKey", objectKey);
        out.put("size", stored);
        return out;
    }

    @PostMapping("/report-images/multipart/abort")
    public Map<String, Object> multipartAbort(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) MultipartAbortRequest body) {
        authService.verify(headerToken, sign, requestTime);
        if (body != null) {
            String objectKey = body.objectKey() == null ? "" : body.objectKey().trim();
            if (objectKey.startsWith(SangongReportImageStorage.PREFIX) && !objectKey.contains("..")) {
                ossClient.abortMultipart(objectKey, body.uploadId());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    @PostMapping("/report-images")
    public Map<String, Object> upload(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) UploadRequest body) {
        authService.verify(headerToken, sign, requestTime);
        if (body == null || body.dataBase64() == null || body.dataBase64().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(body.dataBase64());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_BASE64");
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SIZE");
        }
        if (!ossClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }
        String objectKey = SangongReportImageStorage.buildObjectKey(body.fileName());
        String contentType = body.contentType() == null || body.contentType().isBlank()
            ? "image/jpeg" : body.contentType().trim();
        String url = ossClient.putBytes(objectKey, bytes, contentType);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("url", url);
        out.put("objectKey", objectKey);
        out.put("size", bytes.length);
        return out;
    }

    /** 对象命名与前缀约定，供上传与清理任务共用。 */
    static final class SangongReportImageStorage {
        static final String PREFIX = "sangong/bet-reports/";

        private SangongReportImageStorage() {}

        static String buildObjectKey(String fileName) {
            String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            return PREFIX + day + "/" + sanitize(fileName);
        }

        private static String sanitize(String fileName) {
            String name = fileName == null ? "" : fileName.trim();
            // 只保留安全字符，防止路径穿越/奇异 key
            name = name.replaceAll("[^A-Za-z0-9._-]", "");
            if (name.isEmpty() || name.startsWith(".")) {
                name = "report-" + UUID.randomUUID().toString().replace("-", "") + ".jpg";
            }
            String lower = name.toLowerCase();
            if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")
                && !lower.endsWith(".png") && !lower.endsWith(".webp")) {
                name = name + ".jpg";
            }
            return name;
        }
    }
}
