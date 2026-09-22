package com.chat99.server.platform;

import com.chat99.server.clientversion.AppClientVersion;
import com.chat99.server.clientversion.AppClientVersionRepository;
import com.chat99.server.clientversion.ClientVersionPlatform;
import com.chat99.server.common.ClientContext;
import com.chat99.server.platform.dto.ClientAppContext;
import com.chat99.server.platform.dto.PlatformContactResponse;
import com.chat99.server.platform.dto.UpdateDecision;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 用户端「关于我们」联系信息:官网/邮箱走平台配置,版本/灰度/启动图走运行时数据。 */
@Service
public class PlatformContactService {

    private final PlatformConfigService configService;
    private final AppClientVersionRepository versionRepository;
    private final ClientContext clientContext;
    private final PlatformUpdateResolver updateResolver;
    private final SplashService splashService;
    private final PlatformContactCache cache;

    public PlatformContactService(PlatformConfigService configService,
                                  AppClientVersionRepository versionRepository,
                                  ClientContext clientContext,
                                  PlatformUpdateResolver updateResolver,
                                  SplashService splashService,
                                  PlatformContactCache cache) {
        this.configService = configService;
        this.versionRepository = versionRepository;
        this.clientContext = clientContext;
        this.updateResolver = updateResolver;
        this.splashService = splashService;
        this.cache = cache;
    }

    /** 老签名入口:补一份默认 ClientAppContext 后调新方法。保留向后兼容。 */
    public PlatformController.ContactInfo contact(HttpServletRequest request) {
        PlatformContactResponse resp = contact(
            ClientAppContext.fromRequest(request, clientContext));
        return new PlatformController.ContactInfo(
            resp.website(), resp.email(), resp.version(), resp.build(), resp.downloadUrl());
    }

    /** Controller 入口:query 参数显式优先,缺省回退到请求头。 */
    public PlatformContactResponse contact(HttpServletRequest request,
                                           String deviceId,
                                           String appVersion,
                                           Integer appVersionCode,
                                           String appChannel) {
        return contact(ClientAppContext.fromRequest(
            request, clientContext, deviceId, appVersion, appVersionCode, appChannel));
    }

    /** 新入口:带完整上下文,返回扩展字段。 */
    public PlatformContactResponse contact(ClientAppContext ctx) {
        PlatformContactResponse cached = cache.get(ctx);
        if (cached != null) {
            return cached;
        }
        PlatformContactResponse resp = build(ctx);
        cache.put(ctx, resp);
        return resp;
    }

    private PlatformContactResponse build(ClientAppContext ctx) {
        String website = configService.getWebsite();
        String email = configService.getEmail();
        ClientVersionPlatform platform = ctx.platform();
        Optional<AppClientVersion> latest = versionRepository
            .findFirstByPlatformAndEnabledTrueOrderByPublishedAtDescIdDesc(platform);

        String version;
        String build;
        String downloadUrl;
        if (latest.isEmpty()) {
            version = configService.getVersion();
            build = configService.getBuild();
            downloadUrl = configService.getDownloadUrl();
        } else {
            AppClientVersion row = latest.get();
            version = row.getVersion();
            build = row.getVersionCode() != null
                ? String.valueOf(row.getVersionCode())
                : configService.getBuild();
            downloadUrl = row.getDownloadUrl() != null && !row.getDownloadUrl().isBlank()
                ? row.getDownloadUrl()
                : configService.getDownloadUrl();
        }

        UpdateDecision decision = updateResolver.resolve(ctx);
        SplashService.SplashResponse splash = splashService.resolve(
            platform.apiValue(), ctx.appVersion(), ctx.appChannel());

        return PlatformContactResponse.of(
            website, email, version, build, downloadUrl,
            platform, decision, splash);
    }
}