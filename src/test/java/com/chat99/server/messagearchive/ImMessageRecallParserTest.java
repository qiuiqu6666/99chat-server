package com.chat99.server.messagearchive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImMessageRecallParserTest {

    private ImMessageRecallParser parser;

    @BeforeEach
    void setUp() {
        parser = new ImMessageRecallParser(new ObjectMapper());
    }

    @Test
    void parseC2cRecall() {
        String raw = """
            {
              "CallbackCommand": "C2C.CallbackAfterMsgWithDraw",
              "From_Account": "user_a",
              "To_Account": "user_b",
              "MsgKey": "48374_2837546_1557481126",
              "EventTime": 1670574414123
            }
            """;
        var event = parser.parse(raw, ImMessageRecallParser.CMD_C2C_RECALL);
        assertTrue(event.isPresent());
        assertEquals(1, event.get().msgKeys().size());
        assertEquals("48374_2837546_1557481126", event.get().msgKeys().get(0));
        assertEquals(1670574414123L, event.get().eventTimeMs());
    }

    @Test
    void parseGroupRecall() {
        String raw = """
            {
              "CallbackCommand": "Group.CallbackAfterRecallMsg",
              "GroupId": "1234567890",
              "MsgSeqList": [
                { "MsgSeq": 130, "MsgId": "msg-1" },
                { "MsgSeq": 131, "MsgId": "msg-2" }
              ],
              "EventTime": 1670574414123
            }
            """;
        var event = parser.parse(raw, ImMessageRecallParser.CMD_GROUP_RECALL);
        assertTrue(event.isPresent());
        assertEquals(2, event.get().msgKeys().size());
        assertEquals("1234567890:130", event.get().msgKeys().get(0));
        assertEquals("1234567890:131", event.get().msgKeys().get(1));
    }

    @Test
    void skipGroupRecallWithoutMsgSeq() {
        String raw = """
            {
              "CallbackCommand": "Group.CallbackAfterRecallMsg",
              "GroupId": "1234567890",
              "MsgSeqList": [{ "MsgId": "msg-1" }]
            }
            """;
        assertTrue(parser.parse(raw, ImMessageRecallParser.CMD_GROUP_RECALL).isEmpty());
    }
}
