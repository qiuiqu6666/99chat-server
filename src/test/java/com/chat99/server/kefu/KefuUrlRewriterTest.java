package com.chat99.server.kefu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class KefuUrlRewriterTest {

    @Test
    void rewritesChatwootFrontendUrlsThroughMainService() {
        String body = """
            {"data_url":"http://43.154.162.29/rails/active_storage/blobs/abc/photo.jpg",\
            "thumb_url":"http://43.154.162.29/rails/active_storage/representations/abc/photo.jpg"}
            """;
        String out = KefuUrlRewriter.rewrite(
            body, "http://43.154.162.29", "http://43.154.162.29:8081/kefu");
        assertEquals("""
            {"data_url":"http://43.154.162.29:8081/kefu/rails/active_storage/blobs/abc/photo.jpg",\
            "thumb_url":"http://43.154.162.29:8081/kefu/rails/active_storage/representations/abc/photo.jpg"}
            """, out);
    }

    @Test
    void rewritesLoopbackActiveStorageRedirectThroughMainService() {
        String location =
            "http://127.0.0.1:3000/rails/active_storage/disk/eyJtoken/video.mp4";
        String out = KefuUrlRewriter.rewrite(
            location,
            "http://119.28.179.146:8081/kefu",
            KefuUrlRewriter.upstreamOrigins("http://127.0.0.1:3000", "http://43.154.162.29"));
        assertEquals(
            "http://119.28.179.146:8081/kefu/rails/active_storage/disk/eyJtoken/video.mp4",
            out);
    }

    @Test
    void rewritesFrontendAndLoopbackInSamePayload() {
        String body = """
            {"data_url":"http://43.154.162.29/rails/active_storage/blobs/abc/photo.jpg",\
            "redirect":"http://127.0.0.1:3000/rails/active_storage/disk/abc/photo.jpg"}
            """;
        String out = KefuUrlRewriter.rewrite(
            body,
            "http://119.28.179.146:8081/kefu",
            KefuUrlRewriter.upstreamOrigins("http://127.0.0.1:3000", "http://43.154.162.29"));
        assertEquals("""
            {"data_url":"http://119.28.179.146:8081/kefu/rails/active_storage/blobs/abc/photo.jpg",\
            "redirect":"http://119.28.179.146:8081/kefu/rails/active_storage/disk/abc/photo.jpg"}
            """, out);
    }

    @Test
    void doesNotTreatDifferentPortAsSameOrigin() {
        String body = "http://43.154.162.29:8081/rails/x";
        String out = KefuUrlRewriter.rewrite(
            body, "http://43.154.162.29", "http://43.154.162.29:8081/kefu");
        assertEquals(body, out);
    }

    @Test
    void publicBasePrefersForwardedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/kefu/public/api");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "api.example.com");
        assertEquals(
            "https://api.example.com/kefu",
            KefuUrlRewriter.publicBaseFrom(request, ""));
    }

    @Test
    void configuredPublicBaseWins() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/kefu/public/api");
        assertEquals(
            "http://43.154.162.29:8081/kefu",
            KefuUrlRewriter.publicBaseFrom(request, "http://43.154.162.29:8081/kefu/"));
        assertFalse(KefuUrlRewriter.stripSlash("http://x/").endsWith("/"));
    }
}
