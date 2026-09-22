/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.im;

import com.chat99.server.im.ImCallbackProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ImGroupMessageMonitorService {
    static final String CMD_GROUP_AFTER = "Group.CallbackAfterSendMsg";
    static final String CMD_GROUP_RECALL = "Group.CallbackAfterRecallMsg";
    private static final Logger log = LoggerFactory.getLogger(ImGroupMessageMonitorService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5L);
    private final ImCallbackProperties props;
    private final ObjectMapper json;
    private final Executor executor;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    public ImGroupMessageMonitorService(ImCallbackProperties props, ObjectMapper json, @Qualifier(value="imGroupMonitorExecutor") Executor executor) {
        this.props = props;
        this.json = json;
        this.executor = executor;
    }

    public void tryForwardAsync(String command, String rawBody) {
        Optional payload;
        String groupId;
        if (!this.props.groupMonitorEnabled()) {
            return;
        }
        if (command == null || command.isBlank()) {
            return;
        }
        String url = this.props.groupMonitorUrl() == null ? "" : this.props.groupMonitorUrl().trim();
        String string = groupId = this.props.groupMonitorGroupId() == null ? "" : this.props.groupMonitorGroupId().trim();
        if (url.isEmpty() || groupId.isEmpty()) {
            return;
        }
        switch (command) {
            case "Group.CallbackAfterSendMsg": {
                Optional<Object> optional = this.parseGroupTextMessage(rawBody, groupId);
                break;
            }
            case "Group.CallbackAfterRecallMsg": {
                Optional<Object> optional = this.parseGroupRecall(rawBody, groupId);
                break;
            }
            default: {
                Optional<Object> optional = payload = Optional.empty();
            }
        }
        if (payload.isEmpty()) {
            return;
        }
        Map body = (Map)payload.get();
        this.executor.execute(() -> this.postMonitor(url, body));
    }

    private Optional<Map<String, Object>> parseGroupTextMessage(String rawBody, String watchedGroupId) {
        Map<String, Object> body = this.parseBody(rawBody);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        if (ImGroupMessageMonitorService.intVal(body.get("SendMsgResult")) != 0) {
            return Optional.empty();
        }
        String groupId = ImGroupMessageMonitorService.str(body.get("GroupId"));
        if (groupId == null || !watchedGroupId.equals(groupId.trim())) {
            return Optional.empty();
        }
        String text = ImGroupMessageMonitorService.extractText(body.get("MsgBody"));
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        long msgSeq = ImGroupMessageMonitorService.longVal(body.get("MsgSeq"));
        if (msgSeq <= 0L) {
            log.debug("group monitor skip send without msgSeq groupId={}", (Object)groupId);
            return Optional.empty();
        }
        String imUserId = ImGroupMessageMonitorService.str(body.get("From_Account"));
        if (imUserId == null || imUserId.isBlank()) {
            return Optional.empty();
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("imUserId", imUserId.trim());
        payload.put("groupId", groupId.trim());
        payload.put("text", text.trim());
        payload.put("msgSeq", msgSeq);
        return Optional.of(payload);
    }

    private Optional<Map<String, Object>> parseGroupRecall(String rawBody, String watchedGroupId) {
        Map<String, Object> body = this.parseBody(rawBody);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        String groupId = ImGroupMessageMonitorService.str(body.get("GroupId"));
        if (groupId == null || !watchedGroupId.equals(groupId.trim())) {
            return Optional.empty();
        }
        List<Map<String, Object>> msgSeqList = ImGroupMessageMonitorService.extractRecallMsgSeqList(body.get("MsgSeqList"));
        if (msgSeqList.isEmpty()) {
            return Optional.empty();
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("action", "recall");
        payload.put("groupId", groupId.trim());
        payload.put("msgSeqList", msgSeqList);
        return Optional.of(payload);
    }

    private void postMonitor(String url, Map<String, Object> payload) {
        String body;
        try {
            body = this.json.writeValueAsString(payload);
        }
        catch (JsonProcessingException e) {
            log.warn("group monitor serialize failed groupId={} err={}", payload.get("groupId"), (Object)e.getMessage());
            return;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(HTTP_TIMEOUT).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        String token = this.props.groupMonitorToken();
        if (token != null && !token.isBlank()) {
            builder.header("X-Im-Callback-Key", token.trim());
        }
        String groupId = ImGroupMessageMonitorService.str(payload.get("groupId"));
        try {
            HttpResponse<String> response = this.http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("group monitor forward failed groupId={} action={} status={} body={}", new String[]{groupId, payload.get("action"), response.statusCode(), ImGroupMessageMonitorService.truncate(response.body())});
            } else {
                log.debug("group monitor forwarded groupId={} action={}", (Object)groupId, payload.get("action"));
            }
        }
        catch (Exception e) {
            log.warn("group monitor forward error groupId={} action={} err={}", new String[]{groupId, payload.get("action"), e.getMessage()});
        }
    }

    private Map<String, Object> parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(rawBody, new TypeReference<Map<String, Object>>() {});
        }
        catch (JsonProcessingException e) {
            log.debug("group monitor skip invalid json: {}", (Object)e.getMessage());
            return Map.of();
        }
    }

    private static List<Map<String, Object>> extractRecallMsgSeqList(Object rawList) {
        List list;
        if (!(rawList instanceof List) || (list = (List)rawList).isEmpty()) {
            return List.of();
        }
        ArrayList<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : list) {
            Map m;
            long msgSeq;
            if (!(item instanceof Map) || (msgSeq = ImGroupMessageMonitorService.longVal((m = (Map)item).get("MsgSeq"))) <= 0L) continue;
            LinkedHashMap<String, Long> entry = new LinkedHashMap<String, Long>();
            entry.put("msgSeq", msgSeq);
            out.add(entry);
        }
        return out;
    }

    private static String extractText(Object msgBodyRaw) {
        if (!(msgBodyRaw instanceof List)) {
            return null;
        }
        List msgBody = (List)msgBodyRaw;
        for (Object item : msgBody) {
            Map content;
            String text;
            Object contentRaw;
            Map elem;
            if (!(item instanceof Map) || !"TIMTextElem".equals(ImGroupMessageMonitorService.str((elem = (Map)item).get("MsgType"))) || !((contentRaw = elem.get("MsgContent")) instanceof Map) || (text = ImGroupMessageMonitorService.str((content = (Map)contentRaw).get("Text"))) == null || text.isBlank()) continue;
            return text;
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static int intVal(Object value) {
        String s;
        if (value instanceof Number) {
            Number n = (Number)value;
            return n.intValue();
        }
        if (value instanceof String && !(s = (String)value).isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            }
            catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static long longVal(Object value) {
        String s;
        if (value instanceof Number) {
            Number n = (Number)value;
            return n.longValue();
        }
        if (value instanceof String && !(s = (String)value).isBlank()) {
            try {
                return Long.parseLong(s.trim());
            }
            catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
