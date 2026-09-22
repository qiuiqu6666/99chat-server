package com.chat99.server.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LocationReverseGeocodeService {

    private static final Logger log = LoggerFactory.getLogger(LocationReverseGeocodeService.class);
    private static final int CACHE_MAX = 8192;

    private final LocationProperties props;
    private final UserLocationLatestRepository latestRepository;
    private final UserLocationHistoryRepository historyRepository;
    private final ObjectMapper json;
    private final HttpClient http;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, Place> cache = new ConcurrentHashMap<>();

    public LocationReverseGeocodeService(LocationProperties props,
                                         UserLocationLatestRepository latestRepository,
                                         UserLocationHistoryRepository historyRepository,
                                         ObjectMapper json,
                                         TransactionTemplate transactionTemplate) {
        this.props = props;
        this.latestRepository = latestRepository;
        this.historyRepository = historyRepository;
        this.json = json;
        this.transactionTemplate = transactionTemplate;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(500, props.geocodeTimeoutMs())))
            .build();
    }

    public record Place(String country, String province, String city, String district, String cityLabel) {}

    /** Fire-and-forget after location write; never blocks the upload API. */
    public void resolveAsync(String userId, double lat, double lng, String geohash,
                             Instant collectedAt, Long historyId) {
        if (!props.geocodeEnabled()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Place place = resolve(lat, lng, geohash);
                if (place == null) {
                    return;
                }
                transactionTemplate.executeWithoutResult(status ->
                    persistPlace(userId, collectedAt, historyId, place));
            } catch (Exception e) {
                log.debug("reverse geocode failed userId={}: {}", userId, e.getMessage());
            }
        });
    }

    private void persistPlace(String userId, Instant collectedAt, Long historyId, Place place) {
        latestRepository.findById(userId).ifPresent(row -> {
            if (collectedAt != null && row.getCollectedAt() != null
                && !row.getCollectedAt().equals(collectedAt)) {
                return;
            }
            row.setCountry(place.country());
            row.setProvince(place.province());
            row.setCity(place.city());
            row.setDistrict(place.district());
            row.setCityLabel(place.cityLabel());
            latestRepository.save(row);
        });
        if (historyId != null) {
            historyRepository.findById(historyId).ifPresent(h -> {
                h.setCountry(place.country());
                h.setProvince(place.province());
                h.setCity(place.city());
                h.setDistrict(place.district());
                h.setCityLabel(place.cityLabel());
                historyRepository.save(h);
            });
        }
    }

    Place resolve(double lat, double lng, String geohash) {
        String cacheKey = cacheKey(geohash, lat, lng);
        Place cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Place place = null;
        String amapKey = props.amapKey() == null ? "" : props.amapKey().trim();
        if (!amapKey.isEmpty()) {
            place = fetchAmap(lat, lng, amapKey);
        }
        if (place == null) {
            place = fetchNominatim(lat, lng);
        }
        if (place != null) {
            if (cache.size() >= CACHE_MAX) {
                cache.clear();
            }
            cache.put(cacheKey, place);
        }
        return place;
    }

    private Place fetchAmap(double lat, double lng, String key) {
        try {
            String location = lng + "," + lat;
            String url = "https://restapi.amap.com/v3/geocode/regeo?key="
                + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&location=" + URLEncoder.encode(location, StandardCharsets.UTF_8)
                + "&radius=1000&extensions=base";
            JsonNode root = httpGetJson(url, null);
            if (root == null || !"1".equals(root.path("status").asText())) {
                return null;
            }
            JsonNode component = root.path("regeocode").path("addressComponent");
            String country = textOrNull(component.get("country"));
            String province = textOrNull(component.get("province"));
            String city = amapCity(component.get("city"), province);
            String district = textOrNull(component.get("district"));
            return new Place(country, province, city, district, buildLabel(province, city, district));
        } catch (Exception e) {
            log.debug("amap regeo failed: {}", e.getMessage());
            return null;
        }
    }

    private Place fetchNominatim(double lat, double lng) {
        try {
            String url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2"
                + "&lat=" + lat
                + "&lon=" + lng
                + "&accept-language=zh-CN"
                + "&zoom=10";
            JsonNode root = httpGetJson(url, "99chat-server/1.0 (location reverse-geocode)");
            if (root == null) {
                return null;
            }
            JsonNode addr = root.path("address");
            String country = firstText(addr, "country");
            String province = firstText(addr, "state", "province", "region");
            String city = firstText(addr, "city", "town", "municipality", "county");
            String district = firstText(addr, "suburb", "district", "city_district", "borough");
            return new Place(country, province, city, district, buildLabel(province, city, district));
        } catch (Exception e) {
            log.debug("nominatim regeo failed: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode httpGetJson(String url, String userAgent) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(props.geocodeTimeoutMs()))
            .GET();
        if (userAgent != null && !userAgent.isBlank()) {
            b.header("User-Agent", userAgent);
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
            return null;
        }
        return json.readTree(resp.body());
    }

    private static String amapCity(JsonNode cityNode, String province) {
        String city = textOrNull(cityNode);
        return city != null ? city : province;
    }

    private static String buildLabel(String province, String city, String district) {
        String p = simplifyAdmin(province);
        String c = simplifyAdmin(city);
        String d = simplifyAdmin(district);
        if (c != null && d != null && !c.equals(d)) {
            return c + " " + d;
        }
        if (p != null && c != null && !p.equals(c)) {
            return p + " " + c;
        }
        return Optional.ofNullable(c).or(() -> Optional.ofNullable(p)).or(() -> Optional.ofNullable(d)).orElse(null);
    }

    private static String simplifyAdmin(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        for (String suffix : new String[]{"省", "市", "区", "县"}) {
            if (s.endsWith(suffix) && s.length() > suffix.length() + 1) {
                s = s.substring(0, s.length() - suffix.length());
                break;
            }
        }
        return s.isBlank() ? null : s;
    }

    private static String firstText(JsonNode addr, String... keys) {
        for (String key : keys) {
            String v = textOrNull(addr.get(key));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isArray()) {
            return null;
        }
        String text = node.asText("").trim();
        return text.isEmpty() || "[]".equals(text) ? null : text;
    }

    private static String cacheKey(String geohash, double lat, double lng) {
        if (geohash != null && geohash.length() >= 6) {
            return geohash.substring(0, 6);
        }
        if (geohash != null && !geohash.isBlank()) {
            return geohash;
        }
        return String.format("%.3f,%.3f", lat, lng);
    }
}
