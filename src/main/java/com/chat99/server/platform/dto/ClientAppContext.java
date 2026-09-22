package com.chat99.server.platform.dto;

import com.chat99.server.clientversion.ClientVersionPlatform;
import com.chat99.server.common.ClientContext;
import jakarta.servlet.http.HttpServletRequest;

/** 启动期一次性上下文:平台 + 客户端版本 + 灰度 deviceId + 渠道。 */
public record ClientAppContext(
    ClientVersionPlatform platform,
    String appVersion,
    Integer appVersionCode,
    String deviceId,
    String appChannel
) {
    /** 纯请求头解析:Service 内部默认入口。 */
    public static ClientAppContext fromRequest(HttpServletRequest request, ClientContext clientContext) {
        ClientVersionPlatform pf = ClientVersionPlatform.parse(clientContext.platform(request));
        if (pf == null) pf = ClientVersionPlatform.ANDROID;
        return new ClientAppContext(
            pf,
            header(request, "X-App-Version"),
            parseIntOrNull(header(request, "X-App-Version-Code")),
            header(request, "X-Device-Id"),
            header(request, "X-App-Channel"));
    }

    /** Controller 入口:query 参数显式优先,缺省时回退到请求头。 */
    public static ClientAppContext fromRequest(HttpServletRequest request,
                                               ClientContext clientContext,
                                               String deviceId,
                                               String appVersion,
                                               Integer appVersionCode,
                                               String appChannel) {
        ClientAppContext fromHeaders = fromRequest(request, clientContext);
        return new ClientAppContext(
            fromHeaders.platform(),
            firstNonBlank(appVersion, fromHeaders.appVersion()),
            appVersionCode != null ? appVersionCode : fromHeaders.appVersionCode(),
            firstNonBlank(deviceId, fromHeaders.deviceId()),
            firstNonBlank(appChannel, fromHeaders.appChannel()));
    }

    private static String header(HttpServletRequest request, String name) {
        String h = request.getHeader(name);
        if (h == null || h.isBlank()) return null;
        return h.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static Integer parseIntOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}