package com.chat99.server.wallet;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.wallet.currency-catalog")
public record WalletCurrencyCatalogProperties(List<CurrencyCatalogItem> items) {

    private static final String DEFAULT_USDT_LOGO =
        "https://99chat.oss-cn-hongkong.aliyuncs.com/wallet/usdt.png";
    private static final String DEFAULT_PLATFORM_LOGO =
        "https://99chat.oss-cn-hongkong.aliyuncs.com/wallet/platform.png";

    public WalletCurrencyCatalogProperties {
        if (items == null || items.isEmpty()) {
            items = List.of(
                new CurrencyCatalogItem("99", "99币", DEFAULT_PLATFORM_LOGO,
                    true, false, false, 1),
                new CurrencyCatalogItem("USDT", "USDT", DEFAULT_USDT_LOGO,
                    false, true, true, 2));
        }
    }
}
