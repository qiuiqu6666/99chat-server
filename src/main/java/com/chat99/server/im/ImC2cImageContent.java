package com.chat99.server.im;

/** 腾讯云 IM C2C 图片消息所需元数据。 */
public record ImC2cImageContent(
    String uuid,
    int imageFormat,
    String originUrl,
    long originSize,
    int width,
    int height,
    String largeUrl,
    long largeSize,
    String thumbUrl,
    long thumbSize,
    int thumbWidth,
    int thumbHeight) {}
