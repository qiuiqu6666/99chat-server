package com.chat99.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SangongReportImageStorageTest {

    @Test
    void keepsWebpExtension() {
        String key = IntegrationReportImageController.SangongReportImageStorage
            .buildObjectKey("report-settle-abc.webp");
        assertThat(key).endsWith("/report-settle-abc.webp");
        assertThat(key).doesNotContain(".webp.jpg");
    }

    @Test
    void stillAllowsJpgPngJpeg() {
        assertThat(IntegrationReportImageController.SangongReportImageStorage.buildObjectKey("a.jpg"))
            .endsWith("/a.jpg");
        assertThat(IntegrationReportImageController.SangongReportImageStorage.buildObjectKey("a.jpeg"))
            .endsWith("/a.jpeg");
        assertThat(IntegrationReportImageController.SangongReportImageStorage.buildObjectKey("a.png"))
            .endsWith("/a.png");
    }

    @Test
    void appendsJpgWhenNoImageExt() {
        assertThat(IntegrationReportImageController.SangongReportImageStorage.buildObjectKey("report-x"))
            .endsWith("/report-x.jpg");
    }
}
