package com.chat99.server.feedback;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

final class FeedbackDiagnosticsUpload {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static byte[] read(MultipartFile file, boolean consent) throws IOException {
        if (file == null) return null;
        if (!consent) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DIAGNOSTICS_CONSENT_REQUIRED");
        if (file.getSize() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "DIAGNOSTICS_TOO_LARGE");
        byte[] bytes;
        try (var input = file.getInputStream()) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "DIAGNOSTICS_TOO_LARGE");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (!text.startsWith("Chat recovery report v1\n") || text.indexOf('\0') >= 0)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DIAGNOSTICS");
        }
        return bytes;
    }
}
