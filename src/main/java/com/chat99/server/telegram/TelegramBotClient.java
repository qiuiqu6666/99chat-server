package com.chat99.server.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TelegramBotClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final TelegramOpsProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telegram-ops-notify");
        t.setDaemon(true);
        return t;
    });

    public TelegramBotClient(TelegramOpsProperties props) {
        this.props = props;
    }

    public void sendHtmlAsync(String text) {
        if (!props.isReady() || text == null || text.isBlank()) {
            return;
        }
        String chatId = props.chatId().trim();
        executor.execute(() -> sendHtmlToChat(chatId, text));
    }

    public void sendHtmlToChat(String chatId, String text) {
        if (chatId == null || chatId.isBlank() || text == null || text.isBlank()) {
            return;
        }
        if (props.botToken() == null || props.botToken().isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        try {
            String payload = json.writeValueAsString(body);
            Request request = new Request.Builder()
                .url(apiUrl("sendMessage"))
                .post(RequestBody.create(payload, JSON))
                .build();
            try (Response response = http.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("telegram sendMessage failed status={} body={}", response.code(), truncate(respBody));
                }
            }
        } catch (IOException e) {
            log.warn("telegram sendMessage error: {}", e.getMessage());
        }
    }

    public List<TelegramUpdate> getUpdates(long offset, int timeoutSeconds) throws IOException {
        HttpUrl url = HttpUrl.parse(apiUrl("getUpdates")).newBuilder()
            .addQueryParameter("offset", String.valueOf(offset))
            .addQueryParameter("timeout", String.valueOf(Math.max(0, timeoutSeconds)))
            .addQueryParameter("allowed_updates", "[\"message\"]")
            .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("getUpdates status=" + response.code() + " body=" + truncate(respBody));
            }
            JsonNode root = json.readTree(respBody);
            if (!root.path("ok").asBoolean(false)) {
                throw new IOException("getUpdates not ok: " + truncate(respBody));
            }
            List<TelegramUpdate> updates = new ArrayList<>();
            for (JsonNode node : root.path("result")) {
                long updateId = node.path("update_id").asLong();
                JsonNode message = node.path("message");
                if (message.isMissingNode() || message.isNull()) {
                    updates.add(new TelegramUpdate(updateId, null));
                    continue;
                }
                String chatId = chatIdAsText(message.path("chat").path("id"));
                String text = message.path("text").asText(null);
                if (text == null || text.isBlank()) {
                    // 偶发带 caption 的转发/图片说明，也尝试当文本指令
                    text = message.path("caption").asText(null);
                }
                boolean fromBot = message.path("from").path("is_bot").asBoolean(false);
                updates.add(new TelegramUpdate(updateId, new TelegramMessage(chatId, text, fromBot)));
            }
            return updates;
        }
    }

    private String apiUrl(String method) {
        return "https://api.telegram.org/bot" + props.botToken().trim() + "/" + method;
    }

    /** chat.id 可能是 JSON number，统一转字符串，避免 asText 在部分节点上异常。 */
    private static String chatIdAsText(JsonNode idNode) {
        if (idNode == null || idNode.isNull() || idNode.isMissingNode()) {
            return null;
        }
        if (idNode.isNumber()) {
            return Long.toString(idNode.asLong());
        }
        String s = idNode.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    public record TelegramUpdate(long updateId, TelegramMessage message) {}

    public record TelegramMessage(String chatId, String text, boolean fromBot) {}
}
