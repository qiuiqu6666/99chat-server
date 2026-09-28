package com.chat99.server.sync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ContentDigestTest {

    @Test
    void acceptsMatchingHashAndLength() {
        byte[] data = "photo".getBytes();
        String hash = ContentDigest.sha256Hex(data);
        assertDoesNotThrow(() -> ContentDigest.verify(data, hash, (long) data.length));
    }

    @Test
    void rejectsDifferentBytesAndDeclaredLength() {
        byte[] data = "photo".getBytes();
        assertThrows(ResponseStatusException.class,
            () -> ContentDigest.verify(data, ContentDigest.sha256Hex("other".getBytes()), null));
        assertThrows(ResponseStatusException.class,
            () -> ContentDigest.verify(data, ContentDigest.sha256Hex(data), 9L));
    }

    @Test
    void backupRequiresHashAndMediaType() {
        UserPhoto image = new UserPhoto();
        image.setStatus(1);
        image.setMediaType("IMAGE");
        UserPhoto video = new UserPhoto();
        video.setStatus(1);
        video.setMediaType("VIDEO");
        video.setMimeType("video/mp4");
        assertTrue(BackupMatch.sameContent(image, false));
        assertEquals(false, BackupMatch.sameContent(image, true));
        assertEquals(false, BackupMatch.sameContent(video, false));
        assertTrue(BackupMatch.sameContent(video, true));
        image.setStatus(0);
        assertEquals(false, BackupMatch.sameContent(image, false));
    }
}
