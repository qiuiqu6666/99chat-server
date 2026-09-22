package com.chat99.server.sticker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.oss.ImageProcessor;
import com.chat99.server.oss.OssClient;
import com.chat99.server.sticker.StickerEnums.MediaType;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class StickerUploadServiceTest {

    @Mock OssClient oss;
    @Mock ImageProcessor processor;
    @Mock StickerVideoConverter videoConverter;

    private StickerUploadService service;
    private final StickerProperties props = new StickerProperties(
        "stickers/", 2097152, 5242880, 52428800, 480, 95, 480, 10, 20, 120, true,
        "/www/server/ffmpeg/ffmpeg-6.1/ffmpeg", "/www/server/ffmpeg/ffmpeg-6.1/ffprobe", "user_upload", "我的上传", null,
        "favorites", "收藏", 50, true, false, true, List.of());

    @BeforeEach
    void setUp() {
        service = new StickerUploadService(oss, processor, videoConverter, props);
        when(oss.isConfigured()).thenReturn(true);
    }

    @Test
    void uploadVideo_convertsToGifAndStoresAsGif() throws Exception {
        byte[] video = new byte[] {1, 2, 3};
        byte[] gif = new byte[] {4, 5, 6};
        when(videoConverter.convert(video, "mp4"))
            .thenReturn(new StickerVideoConverter.ConvertResult(gif, 240, 240));
        when(processor.decode(gif)).thenReturn(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB));
        when(processor.resizeKeepAspect(any(), eq(480), eq(95))).thenReturn(new byte[] {9});
        when(oss.putBytes(any(), any(), eq("image/gif"))).thenReturn("https://cdn/origin.gif");
        when(oss.putBytes(any(), any(), eq("image/jpeg"))).thenReturn("https://cdn/thumb.jpg");

        var file = new MockMultipartFile("file", "clip.mp4", "video/mp4", video);
        var result = service.upload("stk_test", file, null);

        assertThat(result.mediaType()).isEqualTo(MediaType.gif);
        assertThat(result.originUrl()).isEqualTo("https://cdn/origin.gif");
        verify(videoConverter).convert(video, "mp4");
    }

    @Test
    void uploadVideo_tooLongRejectedByConverter() throws Exception {
        byte[] video = new byte[] {1, 2, 3};
        when(videoConverter.convert(video, "mp4"))
            .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "VIDEO_TOO_LONG"));

        var file = new MockMultipartFile("file", "clip.mp4", "video/mp4", video);

        assertThatThrownBy(() -> service.upload("stk_test", file, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("VIDEO_TOO_LONG"));
    }
}
