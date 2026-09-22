package com.chat99.server.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_platform_stats")
@Getter
@Setter
@NoArgsConstructor
public class WalletPlatformStats {

    @Id
    private Long id = 1L;

    @Column(name = "total_exchange_surplus_fen", nullable = false)
    private long totalExchangeSurplusFen;

    @Column(name = "total_fee_usdt_micro", nullable = false)
    private long totalFeeUsdtMicro;

    @Column(name = "total_fee_platform_fen", nullable = false)
    private long totalFeePlatformFen;
}
