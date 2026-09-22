package com.chat99.server.oss;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ImageProcessorWebpTest {

    private static final byte[] MINI_WEBP = Base64.getDecoder().decode(
        "UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAwA0JaQAA3AA/vuUAAA=");

    @Test
    void decode_webp() throws Exception {
        BufferedImage img = new ImageProcessor().decode(MINI_WEBP);
        assertThat(img.getWidth()).isGreaterThan(0);
        assertThat(img.getHeight()).isGreaterThan(0);
    }

    @Test
    void toJpeg_fromWebp() throws Exception {
        ImageProcessor processor = new ImageProcessor();
        BufferedImage img = processor.decode(MINI_WEBP);
        byte[] jpeg = processor.toJpeg(img, 85);
        assertThat(jpeg.length).isGreaterThan(100);
    }
}
