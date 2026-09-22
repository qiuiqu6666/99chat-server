package com.chat99.server.wallet;

/** 运营可配置的币种目录项（logo、名称、充提能力等）。 */
public record CurrencyCatalogItem(
    String code,
    String name,
    String logoUrl,
    boolean platformCoin,
    boolean depositEnabled,
    boolean withdrawEnabled,
    int sortOrder) {}
