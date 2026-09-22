package com.chat99.server.platform.dto;

/** 客户端升级决策(强制升级 + 灰度信息)。无记录时所有字段为空 / OPTIONAL。 */
public record UpdateDecision(
    String updateType,
    String minVersion,
    Integer minVersionCode,
    String changelog,
    int grayPercent,
    boolean inGray
) {
    public static UpdateDecision none() {
        return new UpdateDecision("OPTIONAL", null, null, null, 0, false);
    }

    public static UpdateDecision of(String updateType,
                                    String minVersion,
                                    Integer minVersionCode,
                                    String changelog,
                                    int grayPercent,
                                    boolean inGray) {
        return new UpdateDecision(
            updateType == null ? "OPTIONAL" : updateType,
            minVersion,
            minVersionCode,
            changelog,
            grayPercent,
            inGray
        );
    }
}