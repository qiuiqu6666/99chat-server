/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.call;

import com.chat99.server.call.CallProperties;
import com.chat99.server.im.ImAdminClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * @deprecated TRTC/TUICallKit callback registration. Prefer LiveKit
 * ({@code chat99.livekit.*}). Defaults to off via {@code TRTC_CALLBACK_BOOTSTRAP=false}.
 */
@Deprecated
@Component
public class CallCallbackBootstrap
implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CallCallbackBootstrap.class);
    private static final List<String> DEFAULT_COMMANDS = List.of("Call.CallbackAfterEndCall");
    private final CallProperties props;
    private final ImAdminClient imAdminClient;

    public CallCallbackBootstrap(CallProperties props, ImAdminClient imAdminClient) {
        this.props = props;
        this.imAdminClient = imAdminClient;
    }

    public void run(ApplicationArguments args) {
        if (!this.props.enabled() || !this.props.bootstrapOnStartup()) {
            log.info("trtc callback bootstrap skipped: enabled={} bootstrapOnStartup={} (LiveKit is primary)",
                this.props.enabled(), this.props.bootstrapOnStartup());
            return;
        }
        String url = this.props.callbackUrl();
        if (url == null || url.isBlank()) {
            log.info("trtc callback bootstrap skipped: TRTC_CALLBACK_URL not set");
            return;
        }
        String token = this.props.callbackToken();
        if (token == null || token.isBlank()) {
            log.warn("trtc callback bootstrap skipped: TRTC_CALLBACK_TOKEN not set");
            return;
        }
        String callbackUrl = CallCallbackBootstrap.ensureTokenQuery(url.trim(), token.trim());
        try {
            this.imAdminClient.setCallCallback(callbackUrl, DEFAULT_COMMANDS);
            log.info("trtc callback bootstrap ok url={}", (Object)CallCallbackBootstrap.redactToken(callbackUrl));
        }
        catch (Exception e) {
            log.warn("trtc callback bootstrap failed url={}: {}", (Object)CallCallbackBootstrap.redactToken(callbackUrl), (Object)e.getMessage());
        }
    }

    static String ensureTokenQuery(String url, String token) {
        if (url.contains("token=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "token=" + token;
    }

    private static String redactToken(String url) {
        return url.replaceAll("([?&]token=)[^&]*", "$1***");
    }
}
