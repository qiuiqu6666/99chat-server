package com.chat99.server.oss;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ListPartsRequest;
import com.aliyun.oss.model.PartListing;
import com.aliyun.oss.model.PartSummary;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PartETag;
import com.chat99.server.common.AppSettingService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OssClient {

    private static final Logger log = LoggerFactory.getLogger(OssClient.class);

    private final AppSettingService settings;
    private volatile OSS instance;
    private volatile String currentBucket;
    private volatile String currentEndpoint;
    private volatile String currentCdnDomain;

    public OssClient(AppSettingService settings) {
        this.settings = settings;
    }

    public boolean isConfigured() {
        try {
            return getOrInit() != null;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean exists(String objectKey) {
        OSS oss = getOrInit();
        if (oss == null) {
            return false;
        }
        return oss.doesObjectExist(currentBucket, objectKey);
    }

    public byte[] getBytes(String objectKey) throws IOException {
        OSS oss = getOrInit();
        if (oss == null) {
            throw new IllegalStateException("OSS not configured");
        }
        try (OSSObject obj = oss.getObject(currentBucket, objectKey);
             InputStream in = obj.getObjectContent()) {
            return in.readAllBytes();
        }
    }

    public static final String PART_PUT_CONTENT_TYPE = "application/octet-stream";

    public String presignedPutUrl(String objectKey, String contentType, int expireSeconds) {
        OSS oss = getOrInit();
        if (oss == null) {
            throw new IllegalStateException("OSS not configured");
        }
        Date expiry = new Date(System.currentTimeMillis() + expireSeconds * 1000L);
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(currentBucket, objectKey, HttpMethod.PUT);
        req.setExpiration(expiry);
        if (contentType != null && !contentType.isBlank()) {
            req.setContentType(contentType);
        }
        URL url = oss.generatePresignedUrl(req);
        return url.toString();
    }

    public String initiateMultipart(String objectKey, String contentType) {
        OSS oss = getOrInit();
        if (oss == null) {
            throw new IllegalStateException("OSS not configured");
        }
        InitiateMultipartUploadRequest req = new InitiateMultipartUploadRequest(currentBucket, objectKey);
        if (contentType != null && !contentType.isBlank()) {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentType(contentType);
            req.setObjectMetadata(meta);
        }
        return oss.initiateMultipartUpload(req).getUploadId();
    }

    public String presignedPartPutUrl(String objectKey, String uploadId, int partNumber, int expireSeconds) {
        OSS oss = getOrInit();
        if (oss == null) {
            throw new IllegalStateException("OSS not configured");
        }
        Date expiry = new Date(System.currentTimeMillis() + expireSeconds * 1000L);
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(currentBucket, objectKey, HttpMethod.PUT);
        req.setExpiration(expiry);
        req.setContentType(PART_PUT_CONTENT_TYPE);
        req.addQueryParameter("uploadId", uploadId);
        req.addQueryParameter("partNumber", String.valueOf(partNumber));
        return oss.generatePresignedUrl(req).toString();
    }

    public record UploadedPart(int partNumber, String etag, long sizeBytes) {}

    public List<UploadedPart> listMultipartParts(String objectKey, String uploadId) {
        OSS oss = getOrInit();
        if (oss == null) {
            throw new IllegalStateException("OSS not configured");
        }
        List<UploadedPart> out = new ArrayList<>();
        int marker = 0;
        while (true) {
            ListPartsRequest req = new ListPartsRequest(currentBucket, objectKey, uploadId);
            if (marker > 0) {
                req.setPartNumberMarker(marker);
            }
            req.setMaxParts(1000);
            PartListing listing = oss.listParts(req);
            if (listing.getParts() != null) {
                for (PartSummary part : listing.getParts()) {
                    out.add(new UploadedPart(part.getPartNumber(), part.getETag(), part.getSize()));
                }
            }
            if (!listing.isTruncated()) {
                break;
            }
            marker = listing.getNextPartNumberMarker();
        }
        out.sort(java.util.Comparator.comparingInt(UploadedPart::partNumber));
        return out;
    }

    public void completeMultipart(String objectKey, String uploadId, List<PartETag> parts) {
        OSS oss = getOrInit();
        if (oss == null) {
            throw new IllegalStateException("OSS not configured");
        }
        CompleteMultipartUploadRequest req =
            new CompleteMultipartUploadRequest(currentBucket, objectKey, uploadId, parts);
        oss.completeMultipartUpload(req);
    }

    public void abortMultipart(String objectKey, String uploadId) {
        if (objectKey == null || objectKey.isBlank() || uploadId == null || uploadId.isBlank()) {
            return;
        }
        OSS oss = getOrInit();
        if (oss == null) {
            return;
        }
        try {
            oss.abortMultipartUpload(new AbortMultipartUploadRequest(currentBucket, objectKey, uploadId));
        } catch (Exception e) {
            log.warn("OSS abort multipart failed key={}: {}", objectKey, e.getMessage());
        }
    }

    public String putBytes(String objectKey, byte[] data, String contentType) {
        return putBytes(objectKey, data, contentType, null);
    }

    public String putBytes(String objectKey, byte[] data, String contentType, String cacheControl) {
        OSS oss = getOrInit();
        if (oss == null) {
            throw new IllegalStateException("OSS not configured");
        }
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(data.length);
        if (contentType != null) {
            meta.setContentType(contentType);
        }
        if (cacheControl != null && !cacheControl.isBlank()) {
            meta.setCacheControl(cacheControl);
        }
        oss.putObject(currentBucket, objectKey, new ByteArrayInputStream(data), meta);
        return objectUrl(objectKey);
    }

    public String objectUrl(String objectKey) {
        getOrInit();
        return buildUrl(objectKey);
    }

    public long objectContentLength(String objectKey) {
        OSS oss = getOrInit();
        if (oss == null) {
            throw new IllegalStateException("OSS not configured");
        }
        ObjectMetadata meta = oss.getObjectMetadata(currentBucket, objectKey);
        return meta.getContentLength();
    }

    /** 列出指定前缀下的全部对象（分页遍历），用于定时清理等场景。 */
    public List<OSSObjectSummary> listObjects(String prefix) {
        OSS oss = getOrInit();
        if (oss == null) {
            throw new IllegalStateException("OSS not configured");
        }
        List<OSSObjectSummary> out = new ArrayList<>();
        String marker = null;
        do {
            ObjectListing listing = oss.listObjects(new ListObjectsRequest(currentBucket)
                .withPrefix(prefix)
                .withMarker(marker)
                .withMaxKeys(1000));
            out.addAll(listing.getObjectSummaries());
            marker = listing.getNextMarker();
        } while (marker != null && !marker.isBlank());
        return out;
    }

    public void deleteObject(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        OSS oss = getOrInit();
        if (oss == null) {
            return;
        }
        try {
            oss.deleteObject(currentBucket, objectKey);
        } catch (Exception e) {
            log.warn("OSS delete failed key={}: {}", objectKey, e.getMessage());
        }
    }

    public synchronized void invalidate() {
        if (instance != null) {
            try {
                instance.shutdown();
            } catch (Exception e) {
                log.warn("OSS shutdown error: {}", e.getMessage());
            }
            instance = null;
            currentBucket = null;
            currentEndpoint = null;
            currentCdnDomain = null;
        }
    }

    private OSS getOrInit() {
        OSS local = instance;
        if (local != null) return local;
        synchronized (this) {
            if (instance != null) return instance;
            String endpoint = settings.get(AppSettingService.OSS_ENDPOINT).orElse(null);
            String bucket = settings.get(AppSettingService.OSS_BUCKET).orElse(null);
            String ak = settings.get(AppSettingService.OSS_ACCESS_KEY_ID).orElse(null);
            String sk = settings.get(AppSettingService.OSS_ACCESS_KEY_SECRET).orElse(null);
            if (endpoint == null || bucket == null || ak == null || sk == null) {
                log.warn("OSS not configured; skip init");
                return null;
            }
            instance = new OSSClientBuilder().build(normalizeEndpoint(endpoint), ak, sk);
            currentBucket = bucket;
            currentEndpoint = endpoint;
            currentCdnDomain = settings.get(AppSettingService.OSS_CDN_DOMAIN).orElse(null);
            log.info("OSS initialized bucket={} endpoint={}", bucket, endpoint);
            return instance;
        }
    }

    private String buildUrl(String objectKey) {
        String encodedKey = OssPublicUrl.encodeObjectKey(objectKey);
        if (currentCdnDomain != null && !currentCdnDomain.isBlank()) {
            return stripTrailingSlash(ensureScheme(currentCdnDomain)) + "/" + encodedKey;
        }
        return "https://" + currentBucket + "." + stripScheme(currentEndpoint) + "/" + encodedKey;
    }

    private static String normalizeEndpoint(String raw) {
        String s = raw.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        return "https://" + s;
    }

    private static String ensureScheme(String raw) {
        String s = raw.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        return "https://" + s;
    }

    private static String stripScheme(String raw) {
        String s = raw.trim();
        if (s.startsWith("https://")) return s.substring(8);
        if (s.startsWith("http://")) return s.substring(7);
        return s;
    }

    private static String stripTrailingSlash(String raw) {
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }
}
