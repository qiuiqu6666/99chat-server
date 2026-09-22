package com.chat99.sangong.controller;

import com.chat99.sangong.service.GameRealtimeService;
import com.chat99.sangong.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/admin/events")
public class AdminRealtimeController {
    private final GameRealtimeService realtime;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminRealtimeController(GameRealtimeService realtime) {
        this.realtime = realtime;
    }

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("state", realtime.buildSnapshot());
        return out;
    }

    @GetMapping("/stream")
    public ResponseEntity<StreamingResponseBody> stream() {
        int pollMs = realtime.getPollIntervalMs();
        int heartbeatSeconds = realtime.getHeartbeatSeconds();
        // StreamingResponseBody 在异步线程执行，须把请求线程的租户带过去
        String tenantId = TenantContext.require();

        StreamingResponseBody body = (OutputStream os) -> TenantContext.run(tenantId, () -> {
            long lastVersion = -1;
            long lastHeartbeat = System.currentTimeMillis() / 1000;
            try {
                write(os, ": connected\n\n");
                while (!Thread.currentThread().isInterrupted()) {
                    long version = realtime.getVersion();
                    long nowSec = System.currentTimeMillis() / 1000;
                    if (version != lastVersion) {
                        Map<String, Object> snapshot = realtime.buildSnapshot();
                        write(os, "event: state\ndata: " + mapper.writeValueAsString(snapshot) + "\n\n");
                        lastVersion = version;
                        lastHeartbeat = nowSec;
                    } else if (nowSec - lastHeartbeat >= heartbeatSeconds) {
                        write(os, ": heartbeat\n\n");
                        lastHeartbeat = nowSec;
                    }
                    Thread.sleep(pollMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // 客户端断开
            }
        });

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(body);
    }

    private static void write(OutputStream os, String s) throws IOException {
        os.write(s.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
