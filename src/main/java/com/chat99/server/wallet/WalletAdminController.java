package com.chat99.server.wallet;

import com.chat99.server.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/wallet")
public class WalletAdminController {

    private final AdminGuard guard;
    private final WalletFeeConfigRepository feeRepository;
    private final WalletLimitConfigRepository limitRepository;
    private final WalletExchangeConfigRepository exchangeConfigRepository;
    private final WalletPlatformStatsRepository statsRepository;
    private final WalletAccountService accountService;
    private final UserWalletRepository walletRepository;
    private final com.chat99.server.user.UserRepository userRepository;

    public WalletAdminController(AdminGuard guard, WalletFeeConfigRepository feeRepository,
                                 WalletLimitConfigRepository limitRepository,
                                 WalletExchangeConfigRepository exchangeConfigRepository,
                                 WalletPlatformStatsRepository statsRepository,
                                 WalletAccountService accountService,
                                 UserWalletRepository walletRepository,
                                 com.chat99.server.user.UserRepository userRepository) {
        this.guard = guard;
        this.feeRepository = feeRepository;
        this.limitRepository = limitRepository;
        this.exchangeConfigRepository = exchangeConfigRepository;
        this.statsRepository = statsRepository;
        this.accountService = accountService;
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
    }

    public record FeeConfigDto(
        Long id,
        WalletFeeScene scene,
        WalletCurrency currency,
        WalletFeeType feeType,
        long feeValue,
        Long minFee,
        Long maxFee,
        boolean enabled) {}

    public record LimitConfigDto(
        Long id,
        WalletLimitScene scene,
        WalletCurrency currency,
        long perTxMax,
        long dailyMax,
        boolean enabled) {}

    public record ExchangeConfigDto(boolean enabled, int markupBps, int floatBps, long minWithdrawUsdtMicro) {}

    @GetMapping("/fee-config")
    public List<WalletFeeConfig> listFees(HttpServletRequest http) {
        guard.check(http);
        return feeRepository.findAll();
    }

    @PutMapping("/fee-config/{id}")
    public WalletFeeConfig updateFee(@PathVariable long id, @Valid @RequestBody FeeConfigDto dto,
                                     HttpServletRequest http) {
        guard.check(http);
        WalletFeeConfig c = feeRepository.findById(id).orElseThrow();
        c.setFeeType(dto.feeType());
        c.setFeeValue(dto.feeValue());
        c.setMinFee(dto.minFee());
        c.setMaxFee(dto.maxFee());
        c.setEnabled(dto.enabled());
        return feeRepository.save(c);
    }

    @GetMapping("/limit-config")
    public List<WalletLimitConfig> listLimits(HttpServletRequest http) {
        guard.check(http);
        return limitRepository.findAll();
    }

    @PutMapping("/limit-config/{id}")
    public WalletLimitConfig updateLimit(@PathVariable long id, @Valid @RequestBody LimitConfigDto dto,
                                         HttpServletRequest http) {
        guard.check(http);
        WalletLimitConfig c = limitRepository.findById(id).orElseThrow();
        c.setPerTxMax(dto.perTxMax());
        c.setDailyMax(dto.dailyMax());
        c.setEnabled(dto.enabled());
        return limitRepository.save(c);
    }

    @GetMapping("/exchange-config")
    public WalletExchangeConfig getExchangeConfig(HttpServletRequest http) {
        guard.check(http);
        return exchangeConfigRepository.findById(1L).orElseThrow();
    }

    @PutMapping("/exchange-config")
    public WalletExchangeConfig putExchangeConfig(@Valid @RequestBody ExchangeConfigDto dto,
                                                  HttpServletRequest http) {
        guard.check(http);
        WalletExchangeConfig c = exchangeConfigRepository.findById(1L).orElseGet(WalletExchangeConfig::new);
        c.setId(1L);
        c.setEnabled(dto.enabled());
        c.setMarkupBps(dto.markupBps());
        c.setFloatBps(dto.floatBps());
        c.setMinWithdrawUsdtMicro(dto.minWithdrawUsdtMicro());
        return exchangeConfigRepository.save(c);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(HttpServletRequest http) {
        guard.check(http);
        WalletPlatformStats s = statsRepository.findById(1L).orElse(new WalletPlatformStats());
        return Map.of(
            "totalExchangeSurplusFen", s.getTotalExchangeSurplusFen(),
            "totalFeeUsdtMicro", s.getTotalFeeUsdtMicro(),
            "totalFeePlatformFen", s.getTotalFeePlatformFen());
    }

    @PostMapping("/backfill-addresses")
    public Map<String, Object> backfill(HttpServletRequest http) {
        guard.check(http);
        int created = 0;
        for (var u : userRepository.findAll()) {
            if (walletRepository.findById(u.getUserId()).isEmpty()) {
                try {
                    accountService.ensureWalletByUserId(u.getUserId());
                    created++;
                } catch (Exception e) {
                    // 钱包未配置时跳过
                }
            }
        }
        return Map.of("created", created);
    }
}
