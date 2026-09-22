package com.chat99.server.adminapi;

import com.chat99.server.wallet.UserWallet;
import com.chat99.server.wallet.WalletLedgerService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AdminUserFormats {

    private static final DateTimeFormatter DT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private AdminUserFormats() {}

    public static String formatTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return DT.format(instant);
    }

    public static String decimalFromMicro(long micro) {
        BigDecimal v = BigDecimal.valueOf(micro).divide(BigDecimal.valueOf(1_000_000L), 2, RoundingMode.HALF_UP);
        return v.toPlainString();
    }

    public static long parseAmountMicro(String amount) {
        return parseAdjustAmount(AdminAdjustCurrency.USDT, amount);
    }

    public static long parseAdjustAmount(AdminAdjustCurrency currency, String amount) {
        if (amount == null || amount.isBlank()) {
            throw new IllegalArgumentException("amount required");
        }
        BigDecimal v = new BigDecimal(amount.trim());
        if (v.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        int maxScale = currency == AdminAdjustCurrency.TRX ? 6 : 2;
        if (v.scale() > maxScale) {
            throw new IllegalArgumentException("invalid amount precision");
        }
        long factor = currency == AdminAdjustCurrency.TRX ? 1_000_000L
            : currency == AdminAdjustCurrency.USDT ? 1_000_000L
            : 100L;
        return v.multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    public static String formatAdjustBalance(AdminAdjustCurrency currency, long units) {
        return switch (currency) {
            case USDT -> decimalFromMicro(units);
            case TRX -> decimalFromTrxSun(units);
            case CNY -> decimalFromFen(units);
        };
    }

    public static String decimalFromTrxSun(long sun) {
        BigDecimal v = BigDecimal.valueOf(sun).divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
        return v.toPlainString();
    }

    public static String decimalFromFen(long fen) {
        BigDecimal v = BigDecimal.valueOf(fen).divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP);
        return v.toPlainString();
    }

    public static long readAdjustBalance(UserWallet wallet, AdminAdjustCurrency currency) {
        return WalletLedgerService.readBalance(wallet, currency.ledgerCurrency());
    }

    public record WalletSnapshot(
        Map<String, String> walletBalances,
        Map<String, String> walletFrozenByCurrency,
        String walletBalance,
        String walletFrozenAmount) {}

    public static WalletSnapshot walletSnapshot(UserWallet wallet, long frozenUsdtMicro) {
        Map<String, String> balances = new LinkedHashMap<>();
        Map<String, String> frozen = new LinkedHashMap<>();
        if (wallet == null) {
            balances.put("USDT", "0.00");
            balances.put("TRX", "0.000000");
            balances.put("CNY", "0.00");
            frozen.put("USDT", "0.00");
            frozen.put("TRX", "0.000000");
            frozen.put("CNY", "0.00");
            return new WalletSnapshot(balances, frozen, "0.00", "0.00");
        }
        balances.put("USDT", decimalFromMicro(wallet.getBalanceUsdtMicro()));
        balances.put("TRX", decimalFromTrxSun(wallet.getBalanceTrxSun()));
        balances.put("CNY", decimalFromFen(wallet.getBalancePlatformFen()));
        frozen.put("USDT", decimalFromMicro(frozenUsdtMicro));
        frozen.put("TRX", "0.000000");
        frozen.put("CNY", "0.00");
        return new WalletSnapshot(
            balances,
            frozen,
            decimalFromMicro(wallet.getBalanceUsdtMicro()),
            decimalFromMicro(frozenUsdtMicro));
    }

    /**
     * 列表头像展示：优先腾讯云 IM 资料头像（完整 URL），否则库内 avatar_url，最后才退回文件名。
     */
    public static String resolveListAvatar(String imFaceUrl, String dbAvatarUrl) {
        if (imFaceUrl != null && !imFaceUrl.isBlank()) {
            return imFaceUrl.trim();
        }
        if (dbAvatarUrl != null && !dbAvatarUrl.isBlank()) {
            String url = dbAvatarUrl.trim();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return url;
            }
            return avatarFileName(url);
        }
        return null;
    }

    public static String avatarFileName(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return null;
        }
        try {
            String path = URI.create(avatarUrl).getPath();
            if (path == null || path.isBlank()) {
                return avatarUrl;
            }
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (Exception e) {
            int slash = avatarUrl.lastIndexOf('/');
            return slash >= 0 ? avatarUrl.substring(slash + 1) : avatarUrl;
        }
    }

    public static int mapDeviceType(String platform) {
        if (platform == null) {
            return -1;
        }
        return switch (platform.toLowerCase()) {
            case "android" -> 0;
            case "ios" -> 1;
            case "web", "windows", "macos", "linux" -> 2;
            default -> -1;
        };
    }
}
