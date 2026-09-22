package com.chat99.server.livekit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/webhook/livekit")
public class LiveKitWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LiveKitWebhookController.class);

    private final LiveKitProperties props;
    private final LiveKitWebhookVerifier verifier;
    private final LiveKitCallService callService;
    private final ObjectMapper json;

    public LiveKitWebhookController(LiveKitProperties props,
                                    LiveKitWebhookVerifier verifier,
                                    LiveKitCallService callService,
                                    ObjectMapper json) {
        this.props = props;
        this.verifier = verifier;
        this.callService = callService;
        this.json = json;
    }

    @PostMapping({"", "/"})
    public Map<String, Object> handle(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        HttpServletRequest request) throws IOException {
        if (!props.enabled() || !props.webhookEnabled()) {
            return Map.of("ok", true, "skipped", true);
        }
        byte[] bodyBytes = request.getInputStream().readAllBytes();
        verifier.verify(authorization, bodyBytes);
        Map<String, Object> event;
        try {
            event = json.readValue(bodyBytes, new TypeReference<>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_JSON");
        }
        try {
            callService.handleWebhookEvent(event);
        } catch (Exception e) {
            log.warn("livekit webhook handle failed: {}", e.getMessage());
        }
        return Map.of("ok", true);
    }
}
