package com.chat99.server.adminapi;

import com.chat99.server.wallet.WalletFeeConfig;
import com.chat99.server.wallet.WalletFeeConfigRepository;
import com.chat99.server.wallet.WalletFeeType;
import com.chat99.server.wallet.WalletLimitConfig;
import com.chat99.server.wallet.WalletLimitConfigRepository;
import com.chat99.server.wallet.WalletPlatformStats;
import com.chat99.server.wallet.WalletPlatformStatsRepository;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminWalletFeeLimitController {

    private final WalletFeeConfigRepository feeRepository;
    private final WalletLimitConfigRepository limitRepository;
    private final WalletPlatformStatsRepository statsRepository;

    public AdminWalletFeeLimitController(WalletFeeConfigRepository feeRepository,
                                         WalletLimitConfigRepository limitRepository,
                                         WalletPlatformStatsRepository statsRepository) {
        this.feeRepository = feeRepository;
        this.limitRepository = limitRepository;
        this.statsRepository = statsRepository;
    }

    public record FeeConfigDto(
        WalletFeeType feeType,
        long feeValue,
        Long minFee,
        Long maxFee,
        boolean enabled) {}

    public record LimitConfigDto(
        long perTxMax,
        long dailyMax,
        boolean enabled) {}

    @GetMapping("/fee-config")
    public List<WalletFeeConfig> listFees(Authentication auth) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return feeRepository.findAll();
    }

    @PutMapping("/fee-config/{id}")
    public WalletFeeConfig updateFee(Authentication auth,
                                     @PathVariable long id,
                                     @Valid @RequestBody FeeConfigDto dto) {
        AdminAccess.requirePermission(auth, "user.write");
        WalletFeeConfig c = feeRepository.findById(id).orElseThrow();
        c.setFeeType(dto.feeType());
        c.setFeeValue(dto.feeValue());
        c.setMinFee(dto.minFee());
        c.setMaxFee(dto.maxFee());
        c.setEnabled(dto.enabled());
        return feeRepository.save(c);
    }

    @GetMapping("/limit-config")
    public List<WalletLimitConfig> listLimits(Authentication auth) {
        AdminAccess.requirePermission(auth, "wallet.read");
        return limitRepository.findAll();
    }

    @PutMapping("/limit-config/{id}")
    public WalletLimitConfig updateLimit(Authentication auth,
                                         @PathVariable long id,
                                         @Valid @RequestBody LimitConfigDto dto) {
        AdminAccess.requirePermission(auth, "user.write");
        WalletLimitConfig c = limitRepository.findById(id).orElseThrow();
        c.setPerTxMax(dto.perTxMax());
        c.setDailyMax(dto.dailyMax());
        c.setEnabled(dto.enabled());
        return limitRepository.save(c);
    }

    @GetMapping("/platform-stats")
    public Map<String, Object> platformStats(Authentication auth) {
        AdminAccess.requirePermission(auth, "wallet.read");
        WalletPlatformStats s = statsRepository.findById(1L).orElse(new WalletPlatformStats());
        return Map.of(
            "total_exchange_surplus_fen", s.getTotalExchangeSurplusFen(),
            "total_fee_usdt_micro", s.getTotalFeeUsdtMicro(),
            "total_fee_platform_fen", s.getTotalFeePlatformFen());
    }
}
