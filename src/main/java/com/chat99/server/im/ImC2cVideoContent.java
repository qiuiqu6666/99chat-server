package com.chat99.server.im;

/** 腾讯云 IM C2C 视频消息所需元数据。 */
public record ImC2cVideoContent(
    String videoUrl,
    long videoSize,
    int videoSecond,
    String videoFormat,
    String thumbUrl,
    long thumbSize,
    int thumbWidth,
    int thumbHeight) {}
