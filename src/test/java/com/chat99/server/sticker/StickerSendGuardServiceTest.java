package com.chat99.server.sticker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chat99.server.sticker.StickerEnums.StickerStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StickerSendGuardServiceTest {

    @Mock StickerRepository stickerRepository;

    private StickerSendGuardService service;

    @BeforeEach
    void setUp() {
        service = new StickerSendGuardService(stickerRepository, props(true, true, false));
    }

    @Test
    void evaluate_allowsActiveSticker() {
        when(stickerRepository.findById("stk_ok")).thenReturn(Optional.of(activeSticker("stk_ok")));

        var body = faceBody("stk_ok");
        assertThat(service.evaluate(body)).isEmpty();
    }

    @Test
    void evaluate_rejectsMissingStickerWhenEnforced() {
        when(stickerRepository.findById("stk_missing")).thenReturn(Optional.empty());

        var body = faceBody("stk_missing");
        assertThat(service.evaluate(body)).contains("STICKER_NOT_FOUND");
    }

    @Test
    void evaluate_rejectsBannedStickerWhenEnforced() {
        Sticker banned = activeSticker("stk_bad");
        banned.setStatus(StickerStatus.banned);
        when(stickerRepository.findById("stk_bad")).thenReturn(Optional.of(banned));

        var body = faceBody("stk_bad");
        assertThat(service.evaluate(body)).contains("STICKER_UNAVAILABLE");
    }

    @Test
    void evaluate_logOnlyDoesNotReject() {
        service = new StickerSendGuardService(stickerRepository, props(true, false, true));
        when(stickerRepository.findById("stk_missing")).thenReturn(Optional.empty());

        var body = faceBody("stk_missing");
        assertThat(service.evaluate(body)).isEmpty();
    }

    @Test
    void evaluate_disabledSkipsValidation() {
        service = new StickerSendGuardService(stickerRepository, props(false, true, false));

        var body = faceBody("stk_missing");
        assertThat(service.evaluate(body)).isEmpty();
    }

    private static Map<String, Object> faceBody(String stickerId) {
        return Map.of(
            "MsgBody", List.of(
                Map.of(
                    "MsgType", "TIMFaceElem",
                    "MsgContent", Map.of(
                        "Index", 99,
                        "Data", "99chat://sticker/" + stickerId))));
    }

    private static Sticker activeSticker(String id) {
        Sticker sticker = new Sticker();
        sticker.setStickerId(id);
        sticker.setStatus(StickerStatus.active);
        return sticker;
    }

    private static StickerProperties props(boolean enabled, boolean enforce, boolean logOnly) {
        return new StickerProperties(
            "stickers/", 2097152, 5242880, 52428800, 480, 95, 480, 10, 20, 120, true,
            "/www/server/ffmpeg/ffmpeg-6.1/ffmpeg", "/www/server/ffmpeg/ffmpeg-6.1/ffprobe",
            "user_upload", "我的上传", null, "favorites", "收藏", 50,
            enabled, enforce, logOnly, List.of());
    }
}
