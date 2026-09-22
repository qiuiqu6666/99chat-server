package com.chat99.server.moments;

public final class MomentEnums {

    private MomentEnums() {
    }

    public enum MediaType {
        IMAGE,
        VIDEO
    }

    public enum Visibility {
        FRIENDS,
        EXCLUDE,
        PARTIAL
    }

    public enum NotificationType {
        LIKE,
        COMMENT,
        COMMENT_REPLY
    }
}
