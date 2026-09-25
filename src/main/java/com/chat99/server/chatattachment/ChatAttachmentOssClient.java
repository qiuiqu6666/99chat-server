package com.chat99.server.chatattachment;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadResult;
import com.aliyun.oss.model.ListPartsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.PartListing;
import com.aliyun.oss.model.PartSummary;
import com.chat99.server.common.AppSettingService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ChatAttachmentOssClient {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentOssClient.class);

    public static final String PART_PUT_CONTENT_TYPE = "application/octet-stream";

    private final ChatAttachmentProperties props;
    private final AppSettingService settings;
    private volatile OSS instance;
    private volatile String currentBucket;
    private volatile String currentEndpoint;
    private volatile String currentCdnDomain;
    private volatile Boolean ready;

    public ChatAttachmentOssClient(ChatAttachmentProperties props, AppSettingService settings) {
        this.props = props;
        this.settings = settings;
    }

    public boolean isReady() {
        Boolean cached = ready;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (ready != null) {
                return ready;
            }
            try {
                OSS oss = getOrInit();
                if (oss == null || currentBucket == null) {
                    ready = false;
                    return false;
                }
                boolean exists = oss.doesBucketExist(currentBucket);
                if (!exists) {
                    log.error("chat attachment OSS bucket missing bucket={}", currentBucket);
                    ready = false;
                    return false;
                }
                ready = true;
                return true;
            } catch (Exception e) {
                log.error("chat attachment OSS not ready: {}", e.getMessage());
                ready = false;
                return false;
            }
        }
    }

    public String bucket() {
        getOrInit();
        return currentBucket;
    }

    public String initiateMultipart(String objectKey, String contentType) {
        OSS oss = requireOss();
        InitiateMultipartUploadRequest req = new InitiateMultipartUploadRequest(currentBucket, objectKey);
        if (contentType != null && !contentType.isBlank()) {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentType(contentType);
            req.setObjectMetadata(meta);
        }
        InitiateMultipartUploadResult result = oss.initiateMultipartUpload(req);
        return result.getUploadId();
    }

    public String presignPartPut(String objectKey, String providerUploadId, int partNumber, int ttlSeconds) {
        OSS oss = requireOss();
        Date expiry = new Date(System.currentTimeMillis() + ttlSeconds * 1000L);
        GeneratePresignedUrlRequest req =
            new GeneratePresignedUrlRequest(currentBucket, objectKey, HttpMethod.PUT);
        req.setExpiration(expiry);
        req.setContentType(PART_PUT_CONTENT_TYPE);
        req.addQueryParameter("uploadId", providerUploadId);
        req.addQueryParameter("partNumber", String.valueOf(partNumber));
        return httpsUrl(oss.generatePresignedUrl(req));
    }

    public String presignPut(String objectKey, String contentType, int ttlSeconds) {
        OSS oss = requireOss();
        Date expiry = new Date(System.currentTimeMillis() + ttlSeconds * 1000L);
        GeneratePresignedUrlRequest req =
            new GeneratePresignedUrlRequest(currentBucket, objectKey, HttpMethod.PUT);
        req.setExpiration(expiry);
        if (contentType != null && !contentType.isBlank()) {
            req.setContentType(contentType);
        }
        return httpsUrl(oss.generatePresignedUrl(req));
    }

    public String presignGet(String objectKey, int ttlSeconds, String contentType, String contentDisposition) {
        OSS oss = requireOss();
        Date expiry = new Date(System.currentTimeMillis() + ttlSeconds * 1000L);
        GeneratePresignedUrlRequest req =
            new GeneratePresignedUrlRequest(currentBucket, objectKey, HttpMethod.GET);
        req.setExpiration(expiry);
        if (contentType != null && !contentType.isBlank()) {
            req.addQueryParameter("response-content-type", contentType);
        }
        if (contentDisposition != null && !contentDisposition.isBlank()) {
            req.addQueryParameter("response-content-disposition", contentDisposition);
        }
        return httpsUrl(oss.generatePresignedUrl(req));
    }

    public List<StoredPart> listParts(String objectKey, String providerUploadId) {
        OSS oss = requireOss();
        List<StoredPart> out = new ArrayList<>();
        int marker = 0;
        while (true) {
            ListPartsRequest req = new ListPartsRequest(currentBucket, objectKey, providerUploadId);
            if (marker > 0) {
                req.setPartNumberMarker(marker);
            }
            req.setMaxParts(1000);
            PartListing listing = oss.listParts(req);
            if (listing.getParts() != null) {
                for (PartSummary part : listing.getParts()) {
                    out.add(new StoredPart(part.getPartNumber(), part.getSize(), part.getETag()));
                }
            }
            if (!listing.isTruncated()) {
                break;
            }
            marker = listing.getNextPartNumberMarker();
        }
        return out;
    }

    public void completeMultipart(String objectKey, String providerUploadId, List<StoredPart> parts) {
        OSS oss = requireOss();
        List<PartETag> tags = new ArrayList<>();
        for (StoredPart part : parts) {
            tags.add(new PartETag(part.partNumber(), part.etag()));
        }
        CompleteMultipartUploadRequest req =
            new CompleteMultipartUploadRequest(currentBucket, objectKey, providerUploadId, tags);
        oss.completeMultipartUpload(req);
    }

    public void abortMultipart(String objectKey, String providerUploadId) {
        if (objectKey == null || providerUploadId == null || providerUploadId.isBlank()) {
            return;
        }
        OSS oss = getOrInit();
        if (oss == null) {
            return;
        }
        try {
            oss.abortMultipartUpload(new AbortMultipartUploadRequest(currentBucket, objectKey, providerUploadId));
        } catch (Exception e) {
            log.warn("abort multipart failed key={} err={}", objectKey, e.getMessage());
        }
    }

    public HeadResult head(String objectKey) {
        OSS oss = requireOss();
        ObjectMetadata meta = oss.getObjectMetadata(currentBucket, objectKey);
        String crc = null;
        if (meta.getRawMetadata() != null) {
            Object v = meta.getRawMetadata().get("x-oss-hash-crc64ecma");
            if (v != null) {
                crc = v.toString();
            }
        }
        return new HeadResult(meta.getContentLength(), meta.getContentType(), crc);
    }

    public boolean exists(String objectKey) {
        OSS oss = getOrInit();
        if (oss == null) {
            return false;
        }
        return oss.doesObjectExist(currentBucket, objectKey);
    }

    public void setPublicRead(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("object key is required for media access");
        }
        try {
            requireOss().setObjectAcl(currentBucket, objectKey, CannedAccessControlList.PublicRead);
        } catch (Exception e) {
            log.warn("chat attachment public-read failed key={} err={}", objectKey, e.getMessage());
            throw new IllegalStateException("media access configuration failed", e);
        }
    }

    public byte[] getBoundedBytes(String objectKey, long maxBytes) {
        OSS oss = requireOss();
        HeadResult head = head(objectKey);
        if (head.sizeBytes() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        GetObjectRequest req = new GetObjectRequest(currentBucket, objectKey);
        try (OSSObject obj = oss.getObject(req); InputStream in = obj.getObjectContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > maxBytes) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
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
            log.warn("chat attachment delete failed key={} err={}", objectKey, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
    }

    public OSSObject openObject(String objectKey, Long rangeStart, Long rangeEnd) {
        OSS oss = requireOss();
        GetObjectRequest req = new GetObjectRequest(currentBucket, objectKey);
        if (rangeStart != null) {
            // Aliyun OSS: both ends inclusive. end=-1 means through EOF.
            long end = rangeEnd == null ? -1L : rangeEnd;
            req.setRange(rangeStart, end);
        }
        return oss.getObject(req);
    }

    public void putBytes(String objectKey, byte[] data, String contentType) {
        OSS oss = requireOss();
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(data.length);
        if (contentType != null && !contentType.isBlank()) {
            meta.setContentType(contentType);
        }
        oss.putObject(currentBucket, objectKey, new ByteArrayInputStream(data), meta);
    }

    public Instant urlExpiresAt(int ttlSeconds) {
        return Instant.now().plusSeconds(ttlSeconds);
    }

    private OSS requireOss() {
        if (!isReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
        OSS oss = getOrInit();
        if (oss == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
        return oss;
    }

    private OSS getOrInit() {
        OSS local = instance;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (instance != null) {
                return instance;
            }
            ChatAttachmentProperties.Oss ossProps = props.oss();
            String endpoint = firstNonBlank(ossProps.endpoint(),
                settings.get(AppSettingService.OSS_ENDPOINT).orElse(null));
            String ak = firstNonBlank(ossProps.accessKeyId(),
                settings.get(AppSettingService.OSS_ACCESS_KEY_ID).orElse(null));
            String sk = firstNonBlank(ossProps.accessKeySecret(),
                settings.get(AppSettingService.OSS_ACCESS_KEY_SECRET).orElse(null));
            String bucket = ossProps.bucket();
            String publicBucket = settings.get(AppSettingService.OSS_BUCKET).orElse(null);
            if (endpoint == null || ak == null || sk == null || bucket == null) {
                log.warn("chat attachment OSS missing endpoint/bucket/credentials");
                return null;
            }
            if (publicBucket != null && publicBucket.equals(bucket)) {
                log.error("chat attachment bucket must not reuse public OSS bucket");
                return null;
            }
            instance = new OSSClientBuilder().build(normalizeEndpoint(endpoint), ak, sk);
            currentBucket = bucket;
            currentEndpoint = endpoint;
            currentCdnDomain = firstNonBlank(ossProps.cdnDomain(),
                settings.get(AppSettingService.OSS_CDN_DOMAIN).orElse(null));
            log.info("chat attachment OSS initialized bucket={}", bucket);
            return instance;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static String httpsUrl(java.net.URL url) {
        String raw = url.toString();
        if (raw.startsWith("http://")) {
            return "https://" + raw.substring("http://".length());
        }
        return raw;
    }

    private static String normalizeEndpoint(String raw) {
        String s = raw.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        return "https://" + s;
    }

    public record StoredPart(int partNumber, long sizeBytes, String etag) {}

    public record HeadResult(long sizeBytes, String contentType, String crc64) {}
}
