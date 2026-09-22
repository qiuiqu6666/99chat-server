package com.chat99.server.platform;

import com.chat99.server.oss.ImageProcessor;
import com.chat99.server.oss.OssClient;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SplashService {

    private static final Logger log = LoggerFactory.getLogger(SplashService.class);

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");
    private static final int MAX_WIDTH = 1080;
    private static final int MAX_HEIGHT = 1920;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final long MAX_UPLOAD_BYTES = 8L * 1024 * 1024;
    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";
    private static final String OSS_PREFIX = "splash/";

    private final AppSplashConfigRepository repository;
    private final OssClient oss;
    private final ImageProcessor imageProcessor;
    private final Object versionLock = new Object();

    public SplashService(AppSplashConfigRepository repository,
                         OssClient oss,
                         ImageProcessor imageProcessor) {
        this.repository = repository;
        this.oss = oss;
        this.imageProcessor = imageProcessor;
    }

    public SplashResponse resolve(String platform, String appVersion, String channel) {
        Instant now = Instant.now();
        String pf = normalizeToken(platform);
        String ch = normalizeToken(channel);
        String ver = blankToNull(appVersion);

        List<AppSplashConfig> candidates = repository.findByEnabledTrueOrderByUpdatedAtDesc();
        for (AppSplashConfig row : candidates) {
            if (!isInSchedule(row, now)) {
                continue;
            }
            if (!matchesCsv(row.getPlatforms(), pf)) {
                continue;
            }
            if (!matchesCsv(row.getChannels(), ch)) {
                continue;
            }
            if (!meetsMinAppVersion(row.getMinAppVersion(), ver)) {
                continue;
            }
            if (row.getImageUrl() == null || row.getImageUrl().isBlank()) {
                continue;
            }
            return toEnabledResponse(row);
        }
        return SplashResponse.disabled();
    }

    @Transactional
    public AppSplashConfig createFromUpload(MultipartFile file,
                                            Boolean enabled,
                                            String fit,
                                            Instant startAt,
                                            Instant endAt,
                                            String minAppVersion,
                                            String platforms,
                                            String channels,
                                            String createdBy) throws IOException {
        synchronized (versionLock) {
            UploadedAsset asset = uploadValidatedLocked(file);
            AppSplashConfig row = new AppSplashConfig();
            row.setVersion(asset.version());
            row.setEnabled(enabled == null || enabled);
            row.setImageUrl(asset.imageUrl());
            row.setImageMd5(asset.imageMd5());
            row.setContentType(asset.contentType());
            row.setWidth(asset.width());
            row.setHeight(asset.height());
            row.setBytes(asset.bytes());
            row.setFit(normalizeFit(fit));
            row.setStartAt(startAt);
            row.setEndAt(endAt);
            row.setMinAppVersion(blankToNull(minAppVersion));
            row.setPlatforms(normalizeCsv(platforms));
            row.setChannels(normalizeCsv(channels));
            row.setObjectKey(asset.objectKey());
            row.setCreatedBy(blankToNull(createdBy));
            return repository.saveAndFlush(row);
        }
    }

    @Transactional
    public AppSplashConfig update(long id,
                                  Boolean enabled,
                                  String fit,
                                  Instant startAt,
                                  Instant endAt,
                                  Boolean clearStartAt,
                                  Boolean clearEndAt,
                                  String minAppVersion,
                                  String platforms,
                                  String channels) {
        AppSplashConfig row = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "splash_not_found"));
        if (enabled != null) {
            row.setEnabled(enabled);
        }
        if (fit != null) {
            row.setFit(normalizeFit(fit));
        }
        if (Boolean.TRUE.equals(clearStartAt)) {
            row.setStartAt(null);
        } else if (startAt != null) {
            row.setStartAt(startAt);
        }
        if (Boolean.TRUE.equals(clearEndAt)) {
            row.setEndAt(null);
        } else if (endAt != null) {
            row.setEndAt(endAt);
        }
        if (minAppVersion != null) {
            row.setMinAppVersion(blankToNull(minAppVersion));
        }
        if (platforms != null) {
            row.setPlatforms(normalizeCsv(platforms));
        }
        if (channels != null) {
            row.setChannels(normalizeCsv(channels));
        }
        return repository.save(row);
    }

    @Transactional
    public void delete(long id) {
        AppSplashConfig row = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "splash_not_found"));
        String objectKey = row.getObjectKey();
        repository.delete(row);
        if (objectKey != null && !objectKey.isBlank()) {
            oss.deleteObject(objectKey);
            int slash = objectKey.lastIndexOf('/');
            if (slash > 0) {
                String dir = objectKey.substring(0, slash + 1);
                for (String name : List.of("origin.png", "origin.jpg", "origin.jpeg", "origin.webp")) {
                    oss.deleteObject(dir + name);
                }
            }
        }
    }

    public List<AppSplashConfig> listAll() {
        return repository.findAll();
    }

    public Optional<AppSplashConfig> findById(long id) {
        return repository.findById(id);
    }

    public UploadedAsset uploadValidated(MultipartFile file) throws IOException {
        synchronized (versionLock) {
            return uploadValidatedLocked(file);
        }
    }

    private UploadedAsset uploadValidatedLocked(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILE");
        }
        if (!oss.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        String contentType = file.getContentType() == null
            ? "" : file.getContentType().trim().toLowerCase(Locale.ROOT);
        String ext = extension(file.getOriginalFilename());
        if (!ALLOWED_TYPES.contains(contentType) && !ALLOWED_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE_TYPE");
        }

        byte[] raw = file.getBytes();
        BufferedImage decoded;
        try {
            decoded = imageProcessor.decode(raw);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE");
        }

        EncodedSplash encoded = encodeForDelivery(decoded);
        String version = nextVersion();
        String objectKey = OSS_PREFIX + version + "/splash.webp";
        String imageUrl = oss.putBytes(objectKey, encoded.bytes(), "image/webp", CACHE_CONTROL);

        String originExt = ALLOWED_EXT.contains(ext) ? ext : "bin";
        if ("jpeg".equals(originExt)) {
            originExt = "jpg";
        }
        try {
            oss.putBytes(
                OSS_PREFIX + version + "/origin." + originExt,
                raw,
                contentType.isBlank() ? "application/octet-stream" : contentType,
                CACHE_CONTROL);
        } catch (Exception e) {
            log.warn("splash origin archive upload failed: {}", e.getMessage());
        }

        return new UploadedAsset(
            imageUrl,
            md5Hex(encoded.bytes()),
            "image/webp",
            encoded.width(),
            encoded.height(),
            encoded.bytes().length,
            objectKey,
            version);
    }

    private String nextVersion() {
        String day = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = day + "-";
        int seq = 1;
        Optional<AppSplashConfig> last = repository.findTopByVersionStartingWithOrderByVersionDesc(prefix);
        if (last.isPresent()) {
            String ver = last.get().getVersion();
            int dash = ver.lastIndexOf('-');
            if (dash >= 0 && dash + 1 < ver.length()) {
                try {
                    seq = Integer.parseInt(ver.substring(dash + 1)) + 1;
                } catch (NumberFormatException ignored) {
                    seq = 1;
                }
            }
        }
        String candidate;
        do {
            candidate = String.format(Locale.ROOT, "%s-%02d", day, seq);
            seq++;
        } while (repository.existsByVersion(candidate));
        return candidate;
    }

    private EncodedSplash encodeForDelivery(BufferedImage src) throws IOException {
        BufferedImage current = fitWithin(src, MAX_WIDTH, MAX_HEIGHT);
        float[] qualities = {0.90f, 0.80f, 0.70f, 0.60f, 0.50f};
        IOException last = null;
        for (int round = 0; round < 4; round++) {
            for (float q : qualities) {
                try {
                    byte[] bytes = imageProcessor.toWebp(current, q);
                    if (bytes.length <= MAX_BYTES) {
                        return new EncodedSplash(bytes, current.getWidth(), current.getHeight());
                    }
                } catch (IOException e) {
                    last = e;
                }
            }
            int nw = Math.max(1, current.getWidth() * 3 / 4);
            int nh = Math.max(1, current.getHeight() * 3 / 4);
            if (nw == current.getWidth() && nh == current.getHeight()) {
                break;
            }
            current = imageResize(current, nw, nh);
        }
        if (last != null) {
            throw last;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IMAGE_TOO_LARGE_AFTER_COMPRESS");
    }

    private static BufferedImage fitWithin(BufferedImage src, int maxW, int maxH) throws IOException {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxW && h <= maxH) {
            return src;
        }
        double scale = Math.min((double) maxW / w, (double) maxH / h);
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        return imageResize(src, nw, nh);
    }

    private static BufferedImage imageResize(BufferedImage src, int w, int h) throws IOException {
        return net.coobird.thumbnailator.Thumbnails.of(src)
            .size(w, h)
            .asBufferedImage();
    }

    static SplashResponse toEnabledResponse(AppSplashConfig row) {
        return new SplashResponse(
            true,
            row.getVersion(),
            row.getImageUrl(),
            row.getImageMd5(),
            row.getContentType(),
            row.getWidth(),
            row.getHeight(),
            row.getBytes(),
            row.getFit(),
            row.getStartAt(),
            row.getEndAt(),
            row.getMinAppVersion(),
            row.getUpdatedAt());
    }

    private static boolean isInSchedule(AppSplashConfig row, Instant now) {
        if (row.getStartAt() != null && now.isBefore(row.getStartAt())) {
            return false;
        }
        if (row.getEndAt() != null && !now.isBefore(row.getEndAt())) {
            return false;
        }
        return true;
    }

    private static boolean matchesCsv(String csv, String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        if (csv == null || csv.isBlank()) {
            return true;
        }
        return Arrays.stream(csv.split(","))
            .map(SplashService::normalizeToken)
            .anyMatch(token::equals);
    }

    private static boolean meetsMinAppVersion(String minAppVersion, String appVersion) {
        if (minAppVersion == null || minAppVersion.isBlank()) {
            return true;
        }
        if (appVersion == null || appVersion.isBlank()) {
            return true;
        }
        return compareVersions(appVersion, minAppVersion) >= 0;
    }

    /** Compare dotted numeric versions; non-numeric segments compared lexicographically. */
    public static int compareVersions(String a, String b) {
        String[] pa = a.split("[.+\\-]");
        String[] pb = b.split("[.+\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            Integer ia = tryParseInt(sa);
            Integer ib = tryParseInt(sb);
            if (ia != null && ib != null) {
                int c = Integer.compare(ia, ib);
                if (c != 0) {
                    return c;
                }
            } else {
                int c = sa.compareToIgnoreCase(sb);
                if (c != 0) {
                    return c;
                }
            }
        }
        return 0;
    }

    private static Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeFit(String fit) {
        if (fit == null || fit.isBlank()) {
            return "cover";
        }
        return switch (fit.trim().toLowerCase(Locale.ROOT)) {
            case "contain" -> "contain";
            case "fill" -> "fill";
            case "fitwidth", "fit_width" -> "fitWidth";
            case "fitheight", "fit_height" -> "fitHeight";
            default -> "cover";
        };
    }

    private static String normalizeCsv(String raw) {
        if (raw == null) {
            return null;
        }
        String joined = Arrays.stream(raw.split(","))
            .map(SplashService::normalizeToken)
            .filter(s -> s != null && !s.isBlank())
            .distinct()
            .reduce((a, b) -> a + "," + b)
            .orElse("");
        return joined.isBlank() ? null : joined;
    }

    private static String normalizeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private record EncodedSplash(byte[] bytes, int width, int height) {}

    public record UploadedAsset(
        String imageUrl,
        String imageMd5,
        String contentType,
        int width,
        int height,
        int bytes,
        String objectKey,
        String version) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SplashResponse(
        boolean enabled,
        String version,
        String imageUrl,
        String imageMd5,
        String contentType,
        Integer width,
        Integer height,
        Integer bytes,
        String fit,
        Instant startAt,
        Instant endAt,
        String minAppVersion,
        Instant updatedAt) {

        public static SplashResponse disabled() {
            return new SplashResponse(
                false, "default", null, null, null, null, null, null, null, null, null, null, null);
        }
    }
}
