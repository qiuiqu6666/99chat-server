package com.chat99.server.platform;

import com.chat99.server.clientversion.AppClientVersion;
import com.chat99.server.clientversion.AppClientVersionRepository;
import com.chat99.server.platform.dto.ClientAppContext;
import com.chat99.server.platform.dto.UpdateDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;

/** 根据 app_client_version + 灰度比例,计算客户端是否需要强制升级、是否在灰度内。 */
@Service
public class PlatformUpdateResolver {

    private final AppClientVersionRepository versionRepository;
    private final PlatformProperties props;

    public PlatformUpdateResolver(AppClientVersionRepository versionRepository,
                                  PlatformProperties props) {
        this.versionRepository = versionRepository;
        this.props = props;
    }

    public UpdateDecision resolve(ClientAppContext ctx) {
        var latest = versionRepository
            .findFirstByPlatformAndEnabledTrueOrderByPublishedAtDescIdDesc(ctx.platform());
        if (latest.isEmpty()) {
            return UpdateDecision.none();
        }
        AppClientVersion row = latest.get();
        int grayPercent = Math.max(0, Math.min(100, row.getGrayPercent()));
        boolean inGray = isInGrayPercent(ctx.deviceId(), grayPercent, resolveGraySecret());
        String updateType = row.getUpdateType() == null
            ? "OPTIONAL" : row.getUpdateType().name();
        boolean force = "FORCE".equals(updateType) && isBelowMin(
            ctx.appVersion(), ctx.appVersionCode(),
            row.getMinVersion(), row.getMinVersionCode());
        return UpdateDecision.of(
            force ? "FORCE" : updateType,
            row.getMinVersion(),
            row.getMinVersionCode(),
            row.getChangelog(),
            grayPercent,
            inGray);
    }

    /** 客户端版本低于最低版本则视为应强制升级。 */
    static boolean isBelowMin(String clientVer, Integer clientCode,
                              String minVer, Integer minCode) {
        if (minCode != null) {
            if (clientCode == null) return true;
            return clientCode < minCode;
        }
        if (minVer != null && !minVer.isBlank()) {
            if (clientVer == null || clientVer.isBlank()) return true;
            return SplashService.compareVersions(clientVer, minVer) < 0;
        }
        return false;
    }

    /** 灰度命中:secret + deviceId 取 SHA-256 前 4 字节模 100。deviceId 缺省视作全员命中。 */
    static boolean isInGrayPercent(String deviceId, int grayPercent, String secret) {
        if (grayPercent <= 0) return false;
        if (grayPercent >= 100) return true;
        if (deviceId == null || deviceId.isBlank()) return true;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((secret + ":" + deviceId).getBytes(StandardCharsets.UTF_8));
            int v = ((digest[0] & 0xFF) << 24)
                  | ((digest[1] & 0xFF) << 16)
                  | ((digest[2] & 0xFF) << 8)
                  |  (digest[3] & 0xFF);
            return Math.floorMod(v, 100) < grayPercent;
        } catch (NoSuchAlgorithmException e) {
            return true;
        }
    }

    private String resolveGraySecret() {
        return props == null || props.grayHashSecret() == null || props.grayHashSecret().isBlank()
            ? "chat99"
            : props.grayHashSecret();
    }
}