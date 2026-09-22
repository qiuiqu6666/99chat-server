package com.chat99.server.user;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserLocationService {

    private static final Set<String> SOURCES = Set.of("gps", "network", "fused");

    private final UserLocationLatestRepository latestRepository;
    private final UserLocationHistoryRepository historyRepository;
    private final LocationRateLimiter rateLimiter;
    private final LocationReverseGeocodeService geocodeService;
    private final LocationProperties props;

    public UserLocationService(UserLocationLatestRepository latestRepository,
                               UserLocationHistoryRepository historyRepository,
                               LocationRateLimiter rateLimiter,
                               LocationReverseGeocodeService geocodeService,
                               LocationProperties props) {
        this.latestRepository = latestRepository;
        this.historyRepository = historyRepository;
        this.rateLimiter = rateLimiter;
        this.geocodeService = geocodeService;
        this.props = props;
    }

    public record UploadResult(boolean accepted, long nextUploadAfterMs) {}

    public record LocationUpdate(
        Double latitude,
        Double longitude,
        Double accuracy,
        Double altitude,
        Double heading,
        Double speed,
        Long collectedAt,
        String source,
        String deviceId
    ) {}

    @Transactional
    public UploadResult upsert(String userId, LocationUpdate req, String clientIp) {
        validate(req);

        long remaining = rateLimiter.tryAcquireOrRemainingMs(userId);
        if (remaining > 0) {
            return new UploadResult(false, remaining);
        }

        Instant collectedAt = resolveCollectedAt(req.collectedAt());
        Optional<UserLocationLatest> existing = latestRepository.findById(userId);
        if (existing.isPresent()) {
            Instant prev = existing.get().getCollectedAt();
            if (prev != null && !collectedAt.isAfter(prev)) {
                return new UploadResult(true, rateLimiter.intervalMs());
            }
        }

        Instant receivedAt = Instant.now();
        String deviceId = blankToNull(req.deviceId(), 64);
        String source = normalizeSource(req.source());
        String geohash = GeoHash.encode(req.latitude(), req.longitude(), 5);
        String ip = blankToNull(clientIp, 64);

        UserLocationLatest row = existing.orElseGet(UserLocationLatest::new);
        row.setUserId(userId);
        row.setDeviceId(deviceId);
        row.setLatitude(req.latitude());
        row.setLongitude(req.longitude());
        row.setAccuracy(req.accuracy());
        row.setAltitude(req.altitude());
        row.setHeading(req.heading());
        row.setSpeed(req.speed());
        row.setSource(source);
        row.setGeohash(geohash);
        // 逆地理异步回填；坐标变更时先清空旧城市，避免短暂展示错位
        row.setCountry(null);
        row.setProvince(null);
        row.setCity(null);
        row.setDistrict(null);
        row.setCityLabel(null);
        row.setCollectedAt(collectedAt);
        row.setServerReceivedAt(receivedAt);
        row.setIp(ip);
        latestRepository.save(row);

        UserLocationHistory history = new UserLocationHistory();
        history.setUserId(userId);
        history.setDeviceId(deviceId);
        history.setLatitude(req.latitude());
        history.setLongitude(req.longitude());
        history.setAccuracy(req.accuracy());
        history.setAltitude(req.altitude());
        history.setHeading(req.heading());
        history.setSpeed(req.speed());
        history.setSource(source);
        history.setGeohash(geohash);
        history.setCollectedAt(collectedAt);
        history.setServerReceivedAt(receivedAt);
        history.setIp(ip);
        historyRepository.save(history);

        scheduleGeocode(userId, req.latitude(), req.longitude(), geohash, collectedAt, history.getId());
        return new UploadResult(true, rateLimiter.intervalMs());
    }

    private void scheduleGeocode(String userId, double lat, double lng, String geohash,
                                 Instant collectedAt, Long historyId) {
        Runnable task = () -> geocodeService.resolveAsync(userId, lat, lng, geohash, collectedAt, historyId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private void validate(LocationUpdate req) {
        if (req == null || req.latitude() == null || req.longitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION");
        }
        double lat = req.latitude();
        double lng = req.longitude();
        if (Double.isNaN(lat) || Double.isInfinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LATITUDE");
        }
        if (Double.isNaN(lng) || Double.isInfinite(lng) || lng < -180.0 || lng > 180.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LONGITUDE");
        }
        if (req.accuracy() != null) {
            double acc = req.accuracy();
            if (Double.isNaN(acc) || Double.isInfinite(acc) || acc < 0 || acc > props.maxAccuracyMeters()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ACCURACY");
            }
        }
        if (req.heading() != null) {
            double h = req.heading();
            if (Double.isNaN(h) || Double.isInfinite(h) || h < 0 || h > 360) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_HEADING");
            }
        }
        if (req.speed() != null) {
            double s = req.speed();
            if (Double.isNaN(s) || Double.isInfinite(s) || s < 0 || s > 1000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SPEED");
            }
        }
        if (req.source() != null && !req.source().isBlank()) {
            String src = req.source().trim().toLowerCase(Locale.ROOT);
            if (!SOURCES.contains(src)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE");
            }
        }
        if (req.deviceId() != null && req.deviceId().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_ID");
        }
    }

    private Instant resolveCollectedAt(Long collectedAtMs) {
        Instant now = Instant.now();
        if (collectedAtMs == null || collectedAtMs <= 0) {
            return now;
        }
        Instant collected = Instant.ofEpochMilli(collectedAtMs);
        Instant maxFuture = now.plusSeconds(props.maxFutureSkewSeconds());
        if (collected.isAfter(maxFuture)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_COLLECTED_AT");
        }
        return collected;
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        return source.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String raw, int maxLen) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= maxLen ? t : t.substring(0, maxLen);
    }
}
