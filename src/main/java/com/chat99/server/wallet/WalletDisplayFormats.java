package com.chat99.server.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class WalletDisplayFormats {

    private WalletDisplayFormats() {}

    static String fromMicro(long micro) {
        return BigDecimal.valueOf(micro)
            .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP)
            .toPlainString();
    }

    static String fromFen(long fen) {
        return BigDecimal.valueOf(fen)
            .divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP)
            .toPlainString();
    }
}
