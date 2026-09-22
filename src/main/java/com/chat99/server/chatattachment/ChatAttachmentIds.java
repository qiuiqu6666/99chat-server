package com.chat99.server.chatattachment;

import java.util.UUID;

public final class ChatAttachmentIds {

    private ChatAttachmentIds() {}

    public static String attachment() {
        return "att_" + bareUuid();
    }

    public static String upload() {
        return "upl_" + bareUuid();
    }

    public static String reference() {
        return "ref_" + bareUuid();
    }

    public static String nativeVideoOperation() {
        return "nvm_" + bareUuid();
    }

    private static String bareUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
