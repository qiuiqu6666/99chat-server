package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AdminRealtimeSseHub {

    private static final Logger log = LoggerFactory.getLogger(AdminRealtimeSseHub.class);

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper json;

    public AdminRealtimeSseHub(ObjectMapper json) {
        this.json = json;
    }

    public SseEmitter subscribe(String adminUsername) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> remove(emitter));
        emitter.onTimeout(() -> remove(emitter));
        emitter.onError(ex -> remove(emitter));

        sendNamed(emitter, "admin.realtime.connected", Map.of(
            "source", "system",
            "admin", adminUsername == null ? "" : adminUsername));
        log.debug("admin realtime sse connected admin={} active={}", adminUsername, emitters.size());
        return emitter;
    }

    public void broadcast(String event, Map<String, Object> data) {
        if (event == null || event.isBlank() || emitters.isEmpty()) {
            return;
        }
        Map<String, Object> payload = data == null ? Map.of() : new LinkedHashMap<>(data);
        for (SseEmitter emitter : emitters) {
            sendNamed(emitter, event, payload);
        }
    }

    private void remove(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    private void sendNamed(SseEmitter emitter, String event, Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>(data);
        body.put("event", event);
        body.putIfAbsent("time", System.currentTimeMillis());
        try {
            emitter.send(SseEmitter.event().name(event).data(json.writeValueAsString(body)));
        } catch (IOException | IllegalStateException ex) {
            remove(emitter);
            try {
                emitter.completeWithError(ex);
            } catch (Exception ignored) {
                // emitter already closed
            }
        }
    }
}
