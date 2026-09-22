package com.chat99.server.platform.dto;

import com.chat99.server.clientversion.ClientVersionPlatform;
import com.chat99.server.platform.SplashService;
import com.fasterxml.jackson.annotation.JsonInclude;

/** GET /api/v1/platform/contact 响应。字段为 null 时 Jackson 不输出,保持向后兼容。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlatformContactResponse(
    String website,
    String email,
    String version,
    String build,
    String downloadUrl,
    String platform,
    String updateType,
    String minVersion,
    Integer minVersionCode,
    String changelog,
    Integer grayPercent,
    Boolean inGray,
    SplashService.SplashResponse splash
) {
    public static PlatformContactResponse of(String website,
                                             String email,
                                             String version,
                                             String build,
                                             String downloadUrl,
                                             ClientVersionPlatform platform,
                                             UpdateDecision decision,
                                             SplashService.SplashResponse splash) {
        return new PlatformContactResponse(
            website,
            email,
            version,
            build,
            downloadUrl,
            platform == null ? null : platform.apiValue(),
            decision.updateType(),
            decision.minVersion(),
            decision.minVersionCode(),
            decision.changelog(),
            decision.grayPercent() <= 0 ? null : decision.grayPercent(),
            decision.inGray(),
            splash
        );
    }
}