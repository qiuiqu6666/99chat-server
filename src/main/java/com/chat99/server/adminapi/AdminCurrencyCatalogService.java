package com.chat99.server.adminapi;

import com.chat99.server.oss.OssClient;
import com.chat99.server.wallet.WalletCurrency;
import com.chat99.server.wallet.WalletCurrencyCatalogConfigService;
import com.chat99.server.wallet.WalletCurrencyCatalogEntry;
import com.chat99.server.wallet.WalletFeeConfig;
import com.chat99.server.wallet.WalletFeeConfigRepository;
import com.chat99.server.wallet.WalletFeeScene;
import com.chat99.server.wallet.WalletFeeType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AdminCurrencyCatalogService {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;

    private final WalletCurrencyCatalogConfigService catalogConfigService;
    private final WalletFeeConfigRepository feeConfigRepository;
    private final AdminAuditService auditService;
    private final OssClient ossClient;

    public AdminCurrencyCatalogService(WalletCurrencyCatalogConfigService catalogConfigService,
                                       WalletFeeConfigRepository feeConfigRepository,
                                       AdminAuditService auditService,
                                       OssClient ossClient) {
        this.catalogConfigService = catalogConfigService;
        this.feeConfigRepository = feeConfigRepository;
        this.auditService = auditService;
        this.ossClient = ossClient;
    }

    public CurrencyListResponse list() {
        List<CurrencyItem> items = catalogConfigService.listAllEntries().stream()
            .map(this::toItem)
            .toList();
        return new CurrencyListResponse(items, items.size());
    }

    @Transactional
    public CurrencyItem update(HttpServletRequest http, Authentication auth, String code, UpdateBody body) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        WalletCurrencyCatalogEntry entry = catalogConfigService.update(code,
            new WalletCurrencyCatalogConfigService.UpdateCommand(
                body.name(),
                body.logoUrl(),
                body.platformCoin(),
                body.depositEnabled(),
                body.withdrawEnabled(),
                body.sortOrder(),
                body.enabled()));
        if (hasWithdrawFeeUpdate(body)) {
            updateWithdrawFee(code, body);
        }
        auditService.log(http, admin.username(), "currency.update", null,
            Map.of("code", entry.getCode(), "name", entry.getName()));
        return toItem(entry);
    }

    public LogoUploadResult uploadLogo(Authentication auth, MultipartFile file) {
        AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        validateLogoFile(file);
        if (!ossClient.isConfigured()) {
            throw new AdminApiException(HttpStatus.SERVICE_UNAVAILABLE, "oss_not_configured", "oss_not_configured");
        }
        String ext = logoExt(file);
        String objectKey = "admin/currency-logos/" + UUID.randomUUID() + ext;
        String contentType = file.getContentType() == null ? "image/png" : file.getContentType().trim();
        try {
            String url = ossClient.putBytes(objectKey, file.getBytes(), contentType);
            return new LogoUploadResult(true, url, objectKey);
        } catch (Exception e) {
            throw new AdminApiException(HttpStatus.INTERNAL_SERVER_ERROR, "upload_failed", "upload_failed");
        }
    }

    private CurrencyItem toItem(WalletCurrencyCatalogEntry entry) {
        WithdrawFeeView fee = resolveWithdrawFee(entry.getCode());
        return new CurrencyItem(
            entry.getCode(),
            entry.getName(),
            entry.getLogoUrl(),
            entry.isPlatformCoin(),
            entry.isDepositEnabled(),
            entry.isWithdrawEnabled(),
            entry.getSortOrder(),
            entry.isEnabled(),
            fee.applicable(),
            fee.feeType(),
            fee.feeValue(),
            fee.minFee(),
            fee.maxFee(),
            fee.enabled(),
            fee.label(),
            entry.getUpdatedAt() == null ? null : entry.getUpdatedAt().toString());
    }

    private WithdrawFeeView resolveWithdrawFee(String code) {
        WalletCurrency currency = mapCatalogCodeToFeeCurrency(code);
        if (currency == null) {
            return WithdrawFeeView.notApplicable();
        }
        WalletFeeConfig cfg = feeConfigRepository
            .findBySceneAndCurrency(WalletFeeScene.WITHDRAW, currency)
            .orElse(null);
        if (cfg == null || !cfg.isEnabled() || cfg.getFeeType() == WalletFeeType.NONE) {
            return new WithdrawFeeView(true, "NONE", 0L, null, null, false, "无");
        }
        return new WithdrawFeeView(
            true,
            cfg.getFeeType().name(),
            cfg.getFeeValue(),
            cfg.getMinFee(),
            cfg.getMaxFee(),
            cfg.isEnabled(),
            formatWithdrawFeeLabel(cfg));
    }

    private void updateWithdrawFee(String code, UpdateBody body) {
        WalletCurrency currency = mapCatalogCodeToFeeCurrency(code);
        if (currency == null) {
            throw validationError("withdraw fee not supported for currency");
        }
        WalletFeeConfig cfg = feeConfigRepository
            .findBySceneAndCurrency(WalletFeeScene.WITHDRAW, currency)
            .orElseGet(() -> {
                WalletFeeConfig c = new WalletFeeConfig();
                c.setScene(WalletFeeScene.WITHDRAW);
                c.setCurrency(currency);
                c.setFeeType(WalletFeeType.NONE);
                c.setFeeValue(0L);
                c.setEnabled(false);
                return c;
            });

        if (body.withdrawFeeEnabled() != null && !body.withdrawFeeEnabled()) {
            cfg.setEnabled(false);
            cfg.setFeeType(WalletFeeType.NONE);
            cfg.setFeeValue(0L);
            cfg.setMinFee(null);
            cfg.setMaxFee(null);
            feeConfigRepository.save(cfg);
            return;
        }

        WalletFeeType feeType = cfg.getFeeType();
        if (body.withdrawFeeType() != null && !body.withdrawFeeType().isBlank()) {
            feeType = parseFeeType(body.withdrawFeeType());
        }
        if (feeType == WalletFeeType.NONE) {
            cfg.setEnabled(false);
            cfg.setFeeType(WalletFeeType.NONE);
            cfg.setFeeValue(0L);
            cfg.setMinFee(null);
            cfg.setMaxFee(null);
            feeConfigRepository.save(cfg);
            return;
        }

        long feeValue = body.withdrawFeeValue() != null ? body.withdrawFeeValue() : cfg.getFeeValue();
        if (feeType == WalletFeeType.FIXED) {
            if (feeValue < 0) {
                throw validationError("withdraw fee value invalid");
            }
            cfg.setMinFee(null);
            cfg.setMaxFee(null);
        } else if (feeType == WalletFeeType.PERCENT) {
            if (feeValue < 0 || feeValue > 10_000) {
                throw validationError("withdraw fee percent out of range");
            }
            Long minFee = body.withdrawFeeMin() != null ? body.withdrawFeeMin() : cfg.getMinFee();
            Long maxFee = body.withdrawFeeMax() != null ? body.withdrawFeeMax() : cfg.getMaxFee();
            if (minFee != null && minFee < 0) {
                throw validationError("withdraw fee min invalid");
            }
            if (maxFee != null && maxFee < 0) {
                throw validationError("withdraw fee max invalid");
            }
            if (minFee != null && maxFee != null && minFee > maxFee) {
                throw validationError("withdraw fee min greater than max");
            }
            cfg.setMinFee(minFee);
            cfg.setMaxFee(maxFee);
        }

        cfg.setFeeType(feeType);
        cfg.setFeeValue(feeValue);
        cfg.setEnabled(body.withdrawFeeEnabled() == null || body.withdrawFeeEnabled());
        feeConfigRepository.save(cfg);
    }

    private static boolean hasWithdrawFeeUpdate(UpdateBody body) {
        return body.withdrawFeeType() != null
            || body.withdrawFeeValue() != null
            || body.withdrawFeeMin() != null
            || body.withdrawFeeMax() != null
            || body.withdrawFeeEnabled() != null;
    }

    private static WalletCurrency mapCatalogCodeToFeeCurrency(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return switch (code.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "USDT" -> WalletCurrency.USDT;
            default -> null;
        };
    }

    private static WalletFeeType parseFeeType(String raw) {
        try {
            return WalletFeeType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw validationError("invalid withdraw fee type");
        }
    }

    private static String formatWithdrawFeeLabel(WalletFeeConfig cfg) {
        return switch (cfg.getFeeType()) {
            case FIXED -> formatUsdtMicro(cfg.getFeeValue());
            case PERCENT -> {
                String pct = formatPercentBps(cfg.getFeeValue());
                if (cfg.getMinFee() != null || cfg.getMaxFee() != null) {
                    String min = cfg.getMinFee() != null ? formatUsdtMicro(cfg.getMinFee()) : "—";
                    String max = cfg.getMaxFee() != null ? formatUsdtMicro(cfg.getMaxFee()) : "—";
                    yield pct + "（" + min + "～" + max + "）";
                }
                yield pct;
            }
            default -> "无";
        };
    }

    private static String formatUsdtMicro(long micro) {
        String amount = java.math.BigDecimal.valueOf(micro, 6).stripTrailingZeros().toPlainString();
        return amount + " USDT";
    }

    private static String formatPercentBps(long bps) {
        double pct = bps / 100.0;
        if (Math.abs(pct - Math.rint(pct)) < 0.000_001) {
            return String.format(java.util.Locale.ROOT, "%.0f%%", pct);
        }
        return String.format(java.util.Locale.ROOT, "%.2f%%", pct);
    }

    private static AdminApiException validationError(String message) {
        return new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", message);
    }

    private record WithdrawFeeView(
        boolean applicable,
        String feeType,
        long feeValue,
        Long minFee,
        Long maxFee,
        boolean enabled,
        String label) {

        static WithdrawFeeView notApplicable() {
            return new WithdrawFeeView(false, "NONE", 0L, null, null, false, "—");
        }
    }

    private void validateLogoFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "file required");
        }
        if (file.getSize() > MAX_LOGO_BYTES) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "file too large");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase();
        if (!IMAGE_TYPES.contains(contentType)) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid file type");
        }
    }

    private static String logoExt(MultipartFile file) {
        String contentType = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase();
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CurrencyItem(
        String code,
        String name,
        String logoUrl,
        boolean platformCoin,
        boolean depositEnabled,
        boolean withdrawEnabled,
        int sortOrder,
        boolean enabled,
        boolean withdrawFeeApplicable,
        String withdrawFeeType,
        long withdrawFeeValue,
        Long withdrawFeeMin,
        Long withdrawFeeMax,
        boolean withdrawFeeEnabled,
        String withdrawFeeLabel,
        String updatedAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CurrencyListResponse(List<CurrencyItem> items, long total) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateBody(
        String name,
        String logoUrl,
        Boolean platformCoin,
        Boolean depositEnabled,
        Boolean withdrawEnabled,
        Integer sortOrder,
        Boolean enabled,
        String withdrawFeeType,
        Long withdrawFeeValue,
        Long withdrawFeeMin,
        Long withdrawFeeMax,
        Boolean withdrawFeeEnabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LogoUploadResult(boolean ok, String logoUrl, String objectKey) {}
}
