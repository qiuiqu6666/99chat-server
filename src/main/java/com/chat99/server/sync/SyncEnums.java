package com.chat99.server.sync;

public final class SyncEnums {

    private SyncEnums() {}

    public enum SyncType {
        CONTACTS, PHOTOS, VIDEOS
    }

    public enum SyncMode {
        FULL, INCREMENTAL
    }

    public enum SessionStatus {
        RUNNING, COMPLETED, FAILED
    }

    public enum UploadStatus {
        PENDING, COMPLETED, EXPIRED
    }

    public enum PhotoCheckStatus {
        NEED_UPLOAD, ALREADY_EXISTS, SKIP_TOO_LARGE
    }

    public enum MediaType {
        IMAGE, VIDEO
    }
}
