package com.chat99.server.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_exchange_config")
@Getter
@Setter
@NoArgsConstructor
public class WalletExchangeConfig {

    @Id
    private Long id = 1L;

    /** basis points added to Frankfurter USD/CNY (100 = 1%) */
    @Column(name = "markup_bps", nullable = false)
    private int markupBps;

    /** user USDT->platform: multiply rate by (10000 - floatBps) / 10000 */
    @Column(name = "float_bps", nullable = false)
    private int floatBps;

    @Column(name = "min_withdraw_usdt_micro", nullable = false)
    private long minWithdrawUsdtMicro;

    /** 闪兑总开关：false 时 App 闪兑返回维护 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
