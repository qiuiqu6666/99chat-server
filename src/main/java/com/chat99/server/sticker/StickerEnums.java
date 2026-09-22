package com.chat99.server.sticker;

public final class StickerEnums {

    private StickerEnums() {}

    public enum MediaType {
        image, gif
    }

    public enum PackSource {
        system, subscribed, custom
    }

    public enum StickerStatus {
        active, banned, deleted_by_owner
    }
}
