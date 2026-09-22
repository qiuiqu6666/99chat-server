package com.chat99.server.chatattachment;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** 非整数 / 非数字视为未提供，避免客户端传 null 或脏值时 400。 */
final class ChatLenientNumberJson {

    private ChatLenientNumberJson() {}

    static final class LongDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.VALUE_NUMBER_INT) {
                return p.getLongValue();
            }
            if (token == JsonToken.VALUE_STRING) {
                String raw = p.getValueAsString();
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                try {
                    return Long.parseLong(raw.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            p.skipChildren();
            return null;
        }
    }

    static final class IntDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.VALUE_NUMBER_INT) {
                return p.getIntValue();
            }
            if (token == JsonToken.VALUE_STRING) {
                String raw = p.getValueAsString();
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                try {
                    return Integer.parseInt(raw.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            p.skipChildren();
            return null;
        }
    }
}
