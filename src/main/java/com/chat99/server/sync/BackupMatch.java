package com.chat99.server.sync;

final class BackupMatch {

    private BackupMatch() {}

    static boolean sameContent(UserPhoto photo, boolean expectVideo) {
        return photo != null && photo.getStatus() == 1 && expectVideo == MediaSyncTypes.isVideo(photo);
    }
}
