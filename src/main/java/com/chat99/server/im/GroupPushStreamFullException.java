package com.chat99.server.im;

/** 群推送流已到硬上限。Webhook 应回 HTTP 503，不丢已在流里的消息。 */
public class GroupPushStreamFullException extends RuntimeException {

    public GroupPushStreamFullException(String stream) {
        super(stream);
    }
}
