package com.chat99.server.adminapi;

import com.chat99.server.clientversion.AppClientVersion;
import com.chat99.server.clientversion.AppClientVersionRepository;
import com.chat99.server.clientversion.ClientVersionPlatform;
import com.chat99.server.clientversion.ClientVersionUpdateType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminClientVersionService {

    private final AppClientVersionRepository versionRepository;
    private final AdminAuditService auditService;

    public AdminClientVersionService(AppClientVersionRepository versionRepository,
                                     AdminAuditService auditService) {
        this.versionRepository = versionRepository;
        this.auditService = auditService;
    }

    public ClientVersionListResponse list(String platform, String keyword, String enabled,
                                          int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Specification<AppClientVersion> spec = buildSpec(platform, keyword, enabled);
        Page<AppClientVersion> result = versionRepository.findAll(
            spec,
            PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "publishedAt", "createdAt")));
        List<ClientVersionItem> items = result.getContent().stream()
            .map(this::toItem)
            .toList();
        return new ClientVersionListResponse(items, result.getTotalElements(), safePage, safeSize, result.hasNext());
    }

    @Transactional
    public ClientVersionItem create(HttpServletRequest http, Authentication auth, SaveBody body) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        ClientVersionPlatform platform = requirePlatform(body.platform());
        String version = requireVersion(body.version());
        if (versionRepository.findByPlatformAndVersion(platform, version).isPresent()) {
            throw validationError("version already exists for platform");
        }
        AppClientVersion row = new AppClientVersion();
        row.setPlatform(platform);
        row.setVersion(version);
        applyBody(row, body, true);
        versionRepository.save(row);
        auditService.log(http, admin.username(), "client_version.create", null,
            Map.of("id", row.getId(), "platform", platform.apiValue(), "version", version));
        return toItem(row);
    }

    @Transactional
    public ClientVersionItem update(HttpServletRequest http, Authentication auth, long id, SaveBody body) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        AppClientVersion row = requireRow(id);
        ClientVersionPlatform platform = body.platform() != null && !body.platform().isBlank()
            ? requirePlatform(body.platform())
            : row.getPlatform();
        String version = body.version() != null && !body.version().isBlank()
            ? requireVersion(body.version())
            : row.getVersion();
        if (versionRepository.existsByPlatformAndVersionAndIdNot(platform, version, id)) {
            throw validationError("version already exists for platform");
        }
        row.setPlatform(platform);
        row.setVersion(version);
        applyBody(row, body, false);
        versionRepository.save(row);
        auditService.log(http, admin.username(), "client_version.update", null,
            Map.of("id", id, "platform", platform.apiValue(), "version", version));
        return toItem(row);
    }

    @Transactional
    public void delete(HttpServletRequest http, Authentication auth, long id) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        AppClientVersion row = requireRow(id);
        versionRepository.delete(row);
        auditService.log(http, admin.username(), "client_version.delete", null,
            Map.of("id", id, "platform", row.getPlatform().apiValue(), "version", row.getVersion()));
    }

    private AppClientVersion requireRow(long id) {
        return versionRepository.findById(id)
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "not_found", "client_version_not_found"));
    }

    private void applyBody(AppClientVersion row, SaveBody body, boolean creating) {
        if (body.versionCode() != null) {
            if (body.versionCode() < 0) {
                throw validationError("version_code invalid");
            }
            row.setVersionCode(body.versionCode());
        } else if (creating) {
            row.setVersionCode(null);
        }
        if (body.minVersion() != null) {
            row.setMinVersion(blankToNull(body.minVersion()));
        }
        if (body.minVersionCode() != null) {
            if (body.minVersionCode() < 0) {
                throw validationError("min_version_code invalid");
            }
            row.setMinVersionCode(body.minVersionCode());
        }
        if (body.updateType() != null && !body.updateType().isBlank()) {
            ClientVersionUpdateType updateType = ClientVersionUpdateType.parse(body.updateType());
            if (updateType == null) {
                throw validationError("invalid update_type");
            }
            row.setUpdateType(updateType);
        } else if (creating) {
            row.setUpdateType(ClientVersionUpdateType.OPTIONAL);
        }
        if (body.downloadUrl() != null) {
            row.setDownloadUrl(blankToNull(body.downloadUrl()));
        }
        if (body.changelog() != null) {
            row.setChangelog(blankToNull(body.changelog()));
        }
        if (body.enabled() != null) {
            row.setEnabled(body.enabled());
        }
        if (body.grayPercent() != null) {
            int gray = body.grayPercent();
            if (gray < 0 || gray > 100) {
                throw validationError("gray_percent out of range");
            }
            row.setGrayPercent(gray);
        } else if (creating) {
            row.setGrayPercent(100);
        }
        if (body.publishedAt() != null && !body.publishedAt().isBlank()) {
            row.setPublishedAt(Instant.parse(body.publishedAt().trim()));
        }
    }

    private Specification<AppClientVersion> buildSpec(String platform, String keyword, String enabled) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            ClientVersionPlatform pf = ClientVersionPlatform.parse(platform);
            if (pf != null) {
                preds.add(cb.equal(root.get("platform"), pf));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(root.get("version")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("changelog"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("downloadUrl"), "")), like)));
            }
            if ("1".equals(enabled) || "true".equalsIgnoreCase(enabled)) {
                preds.add(cb.isTrue(root.get("enabled")));
            } else if ("0".equals(enabled) || "false".equalsIgnoreCase(enabled)) {
                preds.add(cb.isFalse(root.get("enabled")));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private ClientVersionItem toItem(AppClientVersion row) {
        return new ClientVersionItem(
            String.valueOf(row.getId()),
            row.getPlatform().apiValue(),
            row.getVersion(),
            row.getVersionCode(),
            row.getMinVersion(),
            row.getMinVersionCode(),
            row.getUpdateType().apiValue(),
            row.getDownloadUrl(),
            row.getChangelog(),
            row.isEnabled(),
            row.getGrayPercent(),
            row.getCreatedAt() == null ? null : row.getCreatedAt().toString(),
            row.getPublishedAt() == null ? null : row.getPublishedAt().toString());
    }

    private static ClientVersionPlatform requirePlatform(String raw) {
        ClientVersionPlatform platform = ClientVersionPlatform.parse(raw);
        if (platform == null) {
            throw validationError("invalid platform");
        }
        return platform;
    }

    private static String requireVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            throw validationError("version required");
        }
        String version = raw.trim();
        if (version.length() > 32) {
            throw validationError("version too long");
        }
        return version;
    }

    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static AdminApiException validationError(String message) {
        return new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", message);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ClientVersionItem(
        String id,
        String platform,
        String version,
        Integer versionCode,
        String minVersion,
        Integer minVersionCode,
        String updateType,
        String downloadUrl,
        String changelog,
        boolean enabled,
        int grayPercent,
        String createdAt,
        String publishedAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ClientVersionListResponse(
        List<ClientVersionItem> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SaveBody(
        String platform,
        String version,
        Integer versionCode,
        String minVersion,
        Integer minVersionCode,
        String updateType,
        String downloadUrl,
        String changelog,
        Boolean enabled,
        Integer grayPercent,
        String publishedAt) {}
}
