package com.chat99.server.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImGroupMessageMonitorServiceTest {

    private static final String WATCHED_GROUP = "@TGS#TESTGROUP";

    private ImGroupMessageMonitorService service;
    private CapturingExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CapturingExecutor();
        ImCallbackProperties props = new ImCallbackProperties(
            true, "", "", true, false, List.of(), 0, 48,
            10, 30, 60, 200, 2000, 10, 300,
            true, "https://monitor.example.com/hook", "secret", WATCHED_GROUP);
        service = new ImGroupMessageMonitorService(props, new ObjectMapper(), executor);
    }

    @Test
    void enqueuesMatchingGroupTextMessage() {
        String body = """
            {
              "GroupId": "@TGS#TESTGROUP",
              "From_Account": "user_a",
              "MsgSeq": 130,
              "SendMsgResult": 0,
              "MsgBody": [{ "MsgType": "TIMTextElem", "MsgContent": { "Text": "2.200" } }]
            }
            """;

        service.tryForwardAsync(ImGroupMessageMonitorService.CMD_GROUP_AFTER, body);

        assertEquals(1, executor.tasks.size());
    }

    @Test
    void parseSendPayload_requiresMsgSeq() throws Exception {
        String body = """
            {
              "GroupId": "@TGS#TESTGROUP",
              "From_Account": "user_a",
              "MsgSeq": 130,
              "SendMsgResult": 0,
              "MsgBody": [{ "MsgType": "TIMTextElem", "MsgContent": { "Text": "2.200" } }]
            }
            """;
        Optional<Map<String, Object>> payload = invokeParseSend(body);
        assertTrue(payload.isPresent());
        assertEquals("user_a", payload.get().get("imUserId"));
        assertEquals(WATCHED_GROUP, payload.get().get("groupId"));
        assertEquals("2.200", payload.get().get("text"));
        assertEquals(130L, payload.get().get("msgSeq"));
    }

    @Test
    void parseSendPayload_skipsWithoutMsgSeq() throws Exception {
        String body = """
            {
              "GroupId": "@TGS#TESTGROUP",
              "From_Account": "user_a",
              "SendMsgResult": 0,
              "MsgBody": [{ "MsgType": "TIMTextElem", "MsgContent": { "Text": "x" } }]
            }
            """;
        assertTrue(invokeParseSend(body).isEmpty());
    }

    @Test
    void enqueuesGroupRecall() {
        String body = """
            {
              "GroupId": "@TGS#TESTGROUP",
              "MsgSeqList": [{ "MsgSeq": 130, "MsgId": "msg-1" }]
            }
            """;

        service.tryForwardAsync(ImGroupMessageMonitorService.CMD_GROUP_RECALL, body);

        assertEquals(1, executor.tasks.size());
    }

    @Test
    void parseRecallPayload() throws Exception {
        String body = """
            {
              "GroupId": "@TGS#TESTGROUP",
              "MsgSeqList": [
                { "MsgSeq": 130, "MsgId": "msg-1" },
                { "MsgSeq": 131, "MsgId": "msg-2" }
              ]
            }
            """;
        Optional<Map<String, Object>> payload = invokeParseRecall(body);
        assertTrue(payload.isPresent());
        assertEquals("recall", payload.get().get("action"));
        assertEquals(WATCHED_GROUP, payload.get().get("groupId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgSeqList = (List<Map<String, Object>>) payload.get().get("msgSeqList");
        assertEquals(2, msgSeqList.size());
        assertEquals(130L, msgSeqList.get(0).get("msgSeq"));
        assertEquals(131L, msgSeqList.get(1).get("msgSeq"));
    }

    @Test
    void skipsOtherGroup() throws Exception {
        String body = """
            {
              "GroupId": "@TGS#OTHER",
              "SendMsgResult": 0,
              "MsgSeq": 1,
              "From_Account": "u",
              "MsgBody": [{ "MsgType": "TIMTextElem", "MsgContent": { "Text": "x" } }]
            }
            """;
        assertTrue(invokeParseSend(body).isEmpty());
    }

    @Test
    void skipsNonTextMessage() throws Exception {
        String body = """
            {
              "GroupId": "@TGS#TESTGROUP",
              "SendMsgResult": 0,
              "MsgSeq": 1,
              "From_Account": "u",
              "MsgBody": [{ "MsgType": "TIMImageElem", "MsgContent": {} }]
            }
            """;
        assertTrue(invokeParseSend(body).isEmpty());
    }

    @Test
    void skipsFailedSend() throws Exception {
        String body = """
            {
              "GroupId": "@TGS#TESTGROUP",
              "SendMsgResult": 1,
              "MsgSeq": 1,
              "From_Account": "u",
              "MsgBody": [{ "MsgType": "TIMTextElem", "MsgContent": { "Text": "x" } }]
            }
            """;
        assertTrue(invokeParseSend(body).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> invokeParseSend(String body) throws Exception {
        Method method = ImGroupMessageMonitorService.class.getDeclaredMethod(
            "parseGroupTextMessage", String.class, String.class);
        method.setAccessible(true);
        return (Optional<Map<String, Object>>) method.invoke(service, body, WATCHED_GROUP);
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> invokeParseRecall(String body) throws Exception {
        Method method = ImGroupMessageMonitorService.class.getDeclaredMethod(
            "parseGroupRecall", String.class, String.class);
        method.setAccessible(true);
        return (Optional<Map<String, Object>>) method.invoke(service, body, WATCHED_GROUP);
    }

    private static final class CapturingExecutor implements Executor {
        private final List<Runnable> tasks = new java.util.ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }
    }
}
