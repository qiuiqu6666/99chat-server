package com.chat99.server.call;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AvCallImSignalingParserTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesTuicallKitEnvelopeWithStringData() throws Exception {
        String innerAv = """
            {"businessID":"av_call","call_end":0,"call_type":1,\
            "data":{"cmd":"audioCall","inviter":"caller1","room_id":545879249,\
            "userIDs":["callee1"]},"room_id":545879249,"version":4}
            """;
        String data = json.writeValueAsString(Map.of(
            "actionType", 1,
            "businessID", 1,
            "data", innerAv,
            "inviteID", "invite-abc",
            "inviteeList", List.of("callee1"),
            "inviter", "caller1"));

        Map<String, Object> imBody = imBodyWithData(data, "caller1", "callee1");
        var event = AvCallImSignalingParser.tryParse(json, imBody).orElseThrow();

        assertThat(event.inviteId()).isEqualTo("invite-abc");
        assertThat(event.callerId()).isEqualTo("caller1");
        assertThat(event.calleeId()).isEqualTo("callee1");
        assertThat(event.roomId()).isEqualTo("545879249");
        assertThat(event.cmd()).isEqualTo("audioCall");
        assertThat(AvCallImSignalingParser.isIncomingInvite(event)).isTrue();
    }

    @Test
    void parsesDirectAvCallPayloadUsingImAccounts() throws Exception {
        String data = """
            {"businessID":"av_call","call_end":0,"call_type":1,\
            "data":{"cmd":"videoCall","inviter":"caller2","room_id":999,\
            "userIDs":["callee2"]},"room_id":999}
            """;
        Map<String, Object> imBody = imBodyWithData(data, "caller2", "callee2");
        var event = AvCallImSignalingParser.tryParse(json, imBody).orElseThrow();

        assertThat(event.inviteId()).isEqualTo("room_999_caller2_callee2");
        assertThat(event.callerId()).isEqualTo("caller2");
        assertThat(event.calleeId()).isEqualTo("callee2");
        assertThat(AvCallImSignalingParser.isIncomingInvite(event)).isTrue();
    }

    @Test
    void parsesCompositeInviterId() throws Exception {
        String innerAv = """
            {"businessID":"av_call","call_end":0,"call_type":1,\
            "data":{"cmd":"audioCall","inviter":"acnj6oxey9#0#0#rqwm8onw3j","room_id":545879249,\
            "userIDs":["rqwm8onw3j"]},"room_id":545879249,"version":4}
            """;
        String data = json.writeValueAsString(Map.of(
            "actionType", 1,
            "businessID", 1,
            "data", innerAv,
            "inviteID", "invite-composite",
            "inviteeList", List.of("rqwm8onw3j"),
            "inviter", "acnj6oxey9#0#0#rqwm8onw3j"));

        Map<String, Object> imBody = imBodyWithData(
            data, "acnj6oxey9#0#0#rqwm8onw3j", "rqwm8onw3j");
        var event = AvCallImSignalingParser.tryParse(json, imBody).orElseThrow();

        assertThat(event.inviteId()).isEqualTo("invite-composite");
        assertThat(event.callerId()).isEqualTo("acnj6oxey9");
        assertThat(event.calleeId()).isEqualTo("rqwm8onw3j");
        assertThat(AvCallImSignalingParser.isIncomingInvite(event)).isTrue();
    }

    private static Map<String, Object> imBodyWithData(String data, String from, String to) {
        Map<String, Object> imBody = new LinkedHashMap<>();
        imBody.put("From_Account", from);
        imBody.put("To_Account", to);
        imBody.put("EventTime", 1_700_000_000_000L);
        imBody.put("MsgBody", List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Data", data))));
        return imBody;
    }
}
