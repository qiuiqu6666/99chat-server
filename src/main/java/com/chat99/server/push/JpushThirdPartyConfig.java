package com.chat99.server.push;

/**
 * 极光 Android 厂商通道（options.third_party_channel）配置快照。
 */
public record JpushThirdPartyConfig(
    boolean enabled,
    String huaweiDistribution,
    String honorDistribution,
    String oppoDistribution,
    String vivoDistribution,
    String xiaomiDistribution,
    String xiaomiChannelId) {

    public static JpushThirdPartyConfig disabled() {
        return new JpushThirdPartyConfig(false, "", "", "", "", "", "");
    }
}
