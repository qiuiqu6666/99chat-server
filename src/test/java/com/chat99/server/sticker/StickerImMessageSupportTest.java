package com.chat99.server.sticker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StickerImMessageSupportTest {

    @Test
    void parseStickerIdFromFaceData_supportsQueryParams() {
        assertThat(StickerImMessageSupport.parseStickerIdFromFaceData(
            "99chat://sticker/stk_abc123?thumbUrl=https%3A%2F%2Fcdn.example.com%2Ft.webp"))
            .contains("stk_abc123");
    }

    @Test
    void parseStickerIdFromFaceData_supportsIdOnly() {
        assertThat(StickerImMessageSupport.parseStickerIdFromFaceData("99chat://sticker/stk_only"))
            .contains("stk_only");
    }

    @Test
    void parseStickerIdFromFaceData_ignoresNonScheme() {
        assertThat(StickerImMessageSupport.parseStickerIdFromFaceData("https://cdn.example.com/a.gif"))
            .isEmpty();
    }

    @Test
    void extractStickerIds_readsCustomFaceIndexOnly() {
        Map<String, Object> body = Map.of(
            "MsgBody", List.of(
                Map.of(
                    "MsgType", "TIMFaceElem",
                    "MsgContent", Map.of(
                        "Index", 99,
                        "Data", "99chat://sticker/stk_ok?thumbUrl=x")),
                Map.of(
                    "MsgType", "TIMFaceElem",
                    "MsgContent", Map.of(
                        "Index", 1,
                        "Data", "yz_1")),
                Map.of(
                    "MsgType", "TIMTextElem",
                    "MsgContent", Map.of("Text", "hi"))));

        assertThat(StickerImMessageSupport.extractStickerIds(body)).containsExactly("stk_ok");
    }
}
