package com.chat99.server.im;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupTipImSupportTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void isGroupTipData_acceptsBusinessIdAndCustomType() {
        assertThat(GroupTipImSupport.isGroupTipData(Map.of("businessID", "group_tip"))).isTrue();
        assertThat(GroupTipImSupport.isGroupTipData(Map.of("customType", "group_tip"))).isTrue();
        assertThat(GroupTipImSupport.isGroupTipData(Map.of("type", "GROUP_TIP"))).isTrue();
        assertThat(GroupTipImSupport.isGroupTipData(Map.of("businessID", "wallet_order"))).isFalse();
    }

    @Test
    void isPureGroupTipMessage_trueForSingleTip() throws Exception {
        String data = json.writeValueAsString(Map.of(
            "businessID", "group_tip",
            "action", "member_added"));
        Map<String, Object> body = Map.of(
            "MsgBody", List.of(Map.of(
                "MsgType", "TIMCustomElem",
                "MsgContent", Map.of("Data", data))));
        assertThat(GroupTipImSupport.isPureGroupTipMessage(body, json)).isTrue();
    }

    @Test
    void isPureGroupTipMessage_falseWhenMixedWithText() throws Exception {
        String data = json.writeValueAsString(Map.of("businessID", "group_tip"));
        Map<String, Object> body = Map.of(
            "MsgBody", List.of(
                Map.of(
                    "MsgType", "TIMCustomElem",
                    "MsgContent", Map.of("Data", data)),
                Map.of(
                    "MsgType", "TIMTextElem",
                    "MsgContent", Map.of("Text", "hi"))));
        assertThat(GroupTipImSupport.isPureGroupTipMessage(body, json)).isFalse();
    }

    @Test
    void isPureGroupTipMessage_falseForWalletCard() throws Exception {
        String data = json.writeValueAsString(Map.of("businessID", "wallet_order"));
        Map<String, Object> body = Map.of(
            "MsgBody", List.of(Map.of(
                "MsgType", "TIMCustomElem",
                "MsgContent", Map.of("Data", data))));
        assertThat(GroupTipImSupport.isPureGroupTipMessage(body, json)).isFalse();
    }
}
