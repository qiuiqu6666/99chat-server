package com.chat99.server.aiassistant;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * 读取转发请求体。multipart 可能已被 Spring 解析，此时原始流已空，需按 parts 重组。
 */
final class AiAssistantRequestBodies {

    private AiAssistantRequestBodies() {
    }

    static byte[] read(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        boolean multipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");
        if (multipart) {
            try {
                Collection<Part> parts = request.getParts();
                if (parts != null && !parts.isEmpty()) {
                    return encodeParts(contentType, parts);
                }
            } catch (ServletException | IllegalStateException ignored) {
                // 未解析或容器不支持 parts，改读原始流
            }
        }
        return request.getInputStream().readAllBytes();
    }

    static byte[] encodeParts(String contentType, Collection<Part> parts) throws IOException {
        String boundary = boundaryOf(contentType);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] dashDash = "--".getBytes(StandardCharsets.US_ASCII);
        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.US_ASCII);
        for (Part part : parts) {
            out.write(dashDash);
            out.write(boundaryBytes);
            out.write(crlf);
            StringBuilder disposition = new StringBuilder("Content-Disposition: form-data; name=\"");
            disposition.append(escape(part.getName())).append('"');
            String filename = part.getSubmittedFileName();
            if (filename != null && !filename.isEmpty()) {
                disposition.append("; filename=\"").append(escape(filename)).append('"');
            }
            disposition.append("\r\n");
            out.write(disposition.toString().getBytes(StandardCharsets.UTF_8));
            String partType = part.getContentType();
            if (partType != null && !partType.isEmpty()) {
                out.write(("Content-Type: " + partType + "\r\n").getBytes(StandardCharsets.US_ASCII));
            }
            out.write(crlf);
            try (InputStream in = part.getInputStream()) {
                in.transferTo(out);
            }
            out.write(crlf);
        }
        out.write(dashDash);
        out.write(boundaryBytes);
        out.write(dashDash);
        out.write(crlf);
        return out.toByteArray();
    }

    private static String boundaryOf(String contentType) {
        for (String token : contentType.split(";")) {
            String part = token.trim();
            if (part.length() >= 9 && part.regionMatches(true, 0, "boundary=", 0, 9)) {
                String boundary = part.substring(9).trim();
                if (boundary.length() >= 2 && boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return "----AiAssistantBoundary" + Long.toHexString(System.nanoTime());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
