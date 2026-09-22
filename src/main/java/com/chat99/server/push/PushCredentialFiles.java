package com.chat99.server.push;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class PushCredentialFiles {

    private PushCredentialFiles() {}

    static InputStream openRequired(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IOException("credential path is blank");
        }
        Path file = Path.of(path.trim());
        if (!Files.isRegularFile(file)) {
            throw new IOException("credential file not found: " + file);
        }
        return Files.newInputStream(file);
    }

    static String readOptionalTextFile(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path file = Path.of(path.trim());
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String text = Files.readString(file).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }
}
