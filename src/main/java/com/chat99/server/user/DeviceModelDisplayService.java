package com.chat99.server.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class DeviceModelDisplayService {

    private static final Logger log = LoggerFactory.getLogger(DeviceModelDisplayService.class);
    private static final String APPLE_MODELS_RESOURCE = "device/apple-ios-models.json";

    private final Map<String, String> appleModels;

    public DeviceModelDisplayService(ObjectMapper objectMapper) {
        this.appleModels = loadAppleModels(objectMapper);
    }

    /** 将客户端上报的原始型号转为用户可读名称；未知型号尽量保留原值。 */
    public String display(String platform, String rawModel) {
        if (rawModel == null || rawModel.isBlank()) {
            return platformFallback(platform);
        }
        String trimmed = rawModel.trim();
        if (isGenericPlatformLabel(trimmed)) {
            return platformFallback(platform != null ? platform : trimmed);
        }
        String mapped = lookupApple(trimmed);
        if (mapped != null) {
            return mapped;
        }
        if (isAppleHardwareId(trimmed)) {
            return genericAppleFamily(trimmed);
        }
        return trimmed;
    }

    private String lookupApple(String identifier) {
        String mapped = appleModels.get(identifier);
        if (mapped != null) {
            return mapped;
        }
        return appleModels.get(identifier.toLowerCase(Locale.ROOT));
    }

    private static boolean isGenericPlatformLabel(String value) {
        return "ios".equalsIgnoreCase(value)
            || "android".equalsIgnoreCase(value)
            || "web".equalsIgnoreCase(value);
    }

    private static boolean isAppleHardwareId(String value) {
        return value.matches("(?i)(iPhone|iPad|iPod|Watch|AppleTV|RealityDevice|AudioAccessory)\\d*,\\d+");
    }

    private static String genericAppleFamily(String identifier) {
        String lower = identifier.toLowerCase(Locale.ROOT);
        if (lower.startsWith("iphone")) {
            return "iPhone";
        }
        if (lower.startsWith("ipad")) {
            return "iPad";
        }
        if (lower.startsWith("ipod")) {
            return "iPod touch";
        }
        if (lower.startsWith("watch")) {
            return "Apple Watch";
        }
        if (lower.startsWith("appletv")) {
            return "Apple TV";
        }
        return identifier;
    }

    private static String platformFallback(String platform) {
        if (platform == null || platform.isBlank()) {
            return null;
        }
        return switch (platform.trim().toLowerCase(Locale.ROOT)) {
            case "ios" -> "iPhone";
            case "android" -> "Android";
            case "web" -> "Web";
            default -> platform.trim();
        };
    }

    private static Map<String, String> loadAppleModels(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(APPLE_MODELS_RESOURCE).getInputStream()) {
            Map<String, String> loaded = objectMapper.readValue(in, new TypeReference<>() {});
            Map<String, String> normalized = new HashMap<>();
            for (Map.Entry<String, String> entry : loaded.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    normalized.put(entry.getKey().trim(), entry.getValue().trim());
                }
            }
            return Collections.unmodifiableMap(normalized);
        } catch (Exception e) {
            log.warn("failed to load {}: {}", APPLE_MODELS_RESOURCE, e.getMessage());
            return Map.of();
        }
    }
}
