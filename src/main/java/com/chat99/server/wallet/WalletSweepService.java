package com.chat99.server.wallet;

import com.chat99.server.common.AppSetting;
import com.chat99.server.common.AppSettingRepository;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WalletSweepService {

    /** 链上 USDT 低于此值不归集（0.01 USDT） */
    private static final long MIN_USDT_SWEEP_MICRO = 10_000L;
    /** 归集 TRX 时保留 sun，避免地址完全清空 */
    private static final long TRX_RESERVE_SUN = 1_000_000L;
    private static final String KEY_SWEEP_COLLECT_ADDRESS = "wallet.sweep_collect_address";

    private final Object sweepLock = new Object();

    private final UserWalletRepository walletRepository;
    private final WalletAccountService walletAccountService;
    private final TronGridClient tronGrid;
    private final WalletChainBalanceService chainBalanceService;
    private final WalletConfigService configService;
    private final WalletTronPrepService tronPrepService;
    private final WalletProperties props;
    private final AppSettingRepository appSettingRepository;

    public WalletSweepService(UserWalletRepository walletRepository,
                              WalletAccountService walletAccountService,
                              TronGridClient tronGrid,
                              WalletChainBalanceService chainBalanceService,
                              WalletConfigService configService,
                              WalletTronPrepService tronPrepService,
                              WalletProperties props,
                              AppSettingRepository appSettingRepository) {
        this.walletRepository = walletRepository;
        this.walletAccountService = walletAccountService;
        this.tronGrid = tronGrid;
        this.chainBalanceService = chainBalanceService;
        this.configService = configService;
        this.tronPrepService = tronPrepService;
        this.props = props;
        this.appSettingRepository = appSettingRepository;
    }

    public SweepResult sweep(String userId, boolean sweepUsdt, boolean sweepTrx) {
        synchronized (sweepLock) {
            return sweepInternal(userId, sweepUsdt, sweepTrx);
        }
    }

    private SweepResult sweepInternal(String userId, boolean sweepUsdt, boolean sweepTrx) {
        requireSweepConfigured();
        UserWallet wallet = walletRepository.findById(userId)
            .orElseThrow(() -> WalletExceptions.of(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND"));
        String hotAddress = collectAddress();
        String privateKey = walletAccountService.requirePrivateKeyHex(wallet);
        TronGridClient.AccountBalances chain = tronGrid.fetchAccountBalances(wallet.getTronAddress())
            .orElse(new TronGridClient.AccountBalances(0L, 0L));
        chainBalanceService.applyChainBalance(wallet, chain);
        walletRepository.save(wallet);

        String usdtTxId = null;
        String trxTxId = null;
        long usdtSweptMicro = 0L;
        long trxSweptSun = 0L;

        if (sweepUsdt && chain.usdtMicro() >= MIN_USDT_SWEEP_MICRO) {
            tronPrepService.prepareDepositAddress(wallet.getTronAddress(), chain.trxSun());
            chain = tronGrid.fetchAccountBalances(wallet.getTronAddress())
                .orElse(chain);
            try {
                usdtTxId = tronGrid.broadcastUsdtTransfer(privateKey, hotAddress, chain.usdtMicro());
                usdtSweptMicro = chain.usdtMicro();
            } catch (IOException e) {
                throw WalletExceptions.of(HttpStatus.BAD_GATEWAY, "USDT_SWEEP_FAILED", e.getMessage());
            }
        }

        if (sweepTrx && chain.trxSun() > TRX_RESERVE_SUN * 2) {
            long amount = chain.trxSun() - TRX_RESERVE_SUN;
            try {
                trxTxId = tronGrid.broadcastTrxTransfer(privateKey, hotAddress, amount);
                trxSweptSun = amount;
            } catch (IOException e) {
                if (usdtTxId == null) {
                    throw WalletExceptions.of(HttpStatus.BAD_GATEWAY, "TRX_SWEEP_FAILED", e.getMessage());
                }
            }
        }

        if (usdtTxId == null && trxTxId == null) {
            if (sweepUsdt && chain.usdtMicro() >= MIN_USDT_SWEEP_MICRO) {
                throw WalletExceptions.of(HttpStatus.CONFLICT, "SWEEP_SKIPPED", "归集失败，请确认地址有足够 TRX 支付手续费");
            }
            throw WalletExceptions.of(HttpStatus.CONFLICT, "SWEEP_NOTHING", "无可归集余额");
        }

        return new SweepResult(
            wallet.getUserId(),
            wallet.getTronAddress(),
            usdtSweptMicro,
            trxSweptSun,
            usdtTxId,
            trxTxId);
    }

    public String hotWalletAddress() {
        requireSweepConfigured();
        return TronTransactionSigner.privateKeyToBase58Address(configService.getHotWalletPrivateKey().trim());
    }

    /** 归集目标：配置了独立收款地址则用它，否则回退热钱包。 */
    public String collectAddress() {
        String configured = appSettingRepository.findById(KEY_SWEEP_COLLECT_ADDRESS)
            .map(AppSetting::getValue)
            .filter(v -> v != null && !v.isBlank())
            .orElseGet(() -> props.sweepCollectAddress());
        if (configured != null && !configured.isBlank()) {
            String address = configured.trim();
            if (!TronAddressUtils.isValidTronAddress(address)) {
                throw WalletExceptions.of(HttpStatus.PRECONDITION_FAILED, "SWEEP_COLLECT_ADDRESS_INVALID");
            }
            return address;
        }
        return hotWalletAddress();
    }

    public boolean isReady() {
        return configService.isDepositMnemonicConfigured() && configService.isHotWalletConfigured();
    }

    public void requireSweepConfigured() {
        if (!configService.isDepositMnemonicConfigured()) {
            throw WalletExceptions.of(HttpStatus.PRECONDITION_FAILED, "DEPOSIT_MNEMONIC_NOT_CONFIGURED");
        }
        if (!configService.isHotWalletConfigured()) {
            throw WalletExceptions.of(HttpStatus.PRECONDITION_FAILED, "HOT_WALLET_NOT_CONFIGURED");
        }
    }

    public record SweepResult(
        String userId,
        String fromAddress,
        long usdtSweptMicro,
        long trxSweptSun,
        String usdtTxId,
        String trxTxId) {}
}
