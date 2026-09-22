package com.chat99.server.messagearchive;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImMessageArchiveEvent(
    String eventId,
    String sdkAppId,
    String callbackCommand,
    String msgKey,
    String chatType,
    String fromAccount,
    String peerAccount,
    String groupId,
    Long msgSeq,
    long msgTimeMs,
    String elemType,
    String previewText,
    String msgBodyJson,
    String rawBody,
    long receivedAtMs,
    /** 腾讯真正的 MsgId；可空。禁止用 MsgKey 冒充。 */
    String msgId) {}
