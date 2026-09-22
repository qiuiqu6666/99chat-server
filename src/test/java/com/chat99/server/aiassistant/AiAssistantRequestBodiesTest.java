package com.chat99.server.aiassistant;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiAssistantRequestBodiesTest {

    @Test
    void encodePartsKeepsFileFieldName() throws Exception {
        Part part = new MemoryPart("file", "a.jpg", "image/jpeg", new byte[] {1, 2, 3});
        byte[] body = AiAssistantRequestBodies.encodeParts(
            "multipart/form-data; boundary=----test",
            List.of(part));
        String text = new String(body, StandardCharsets.UTF_8);
        assertThat(text).contains("name=\"file\"");
        assertThat(text).contains("filename=\"a.jpg\"");
        assertThat(text).contains("Content-Type: image/jpeg");
        assertThat(body).containsSequence((byte) 1, (byte) 2, (byte) 3);
    }

    private static final class MemoryPart implements Part {
        private final String name;
        private final String filename;
        private final String contentType;
        private final byte[] data;

        MemoryPart(String name, String filename, String contentType, byte[] data) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.data = data;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(data);
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getSubmittedFileName() {
            return filename;
        }

        @Override
        public long getSize() {
            return data.length;
        }

        @Override
        public void write(String fileName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete() {
        }

        @Override
        public String getHeader(String name) {
            return null;
        }

        @Override
        public Collection<String> getHeaders(String name) {
            return List.of();
        }

        @Override
        public Collection<String> getHeaderNames() {
            return List.of();
        }
    }
}
