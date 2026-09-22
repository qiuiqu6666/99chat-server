package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.MediaType;
import java.util.Locale;

public final class MediaSyncTypes {

    private MediaSyncTypes() {}

    public static boolean isVideo(String mediaType, String mimeType) {
        if (mediaType != null && !mediaType.isBlank()
            && MediaType.VIDEO.name().equalsIgnoreCase(mediaType.trim())) {
            return true;
        }
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("video/");
    }

    public static boolean isVideo(UserPhoto photo) {
        if (photo == null) {
            return false;
        }
        return isVideo(photo.getMediaType(), photo.getMimeType());
    }

    public static String resolveMediaType(String mediaType, String mimeType) {
        if (isVideo(mediaType, mimeType)) {
            return MediaType.VIDEO.name();
        }
        if (mediaType != null && !mediaType.isBlank()) {
            return mediaType.trim().toUpperCase(Locale.ROOT);
        }
        return MediaType.IMAGE.name();
    }
}
