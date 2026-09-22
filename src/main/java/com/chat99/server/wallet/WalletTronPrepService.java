package com.chat99.server.wallet;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WalletTronPrepService {

    private static final Logger log = LoggerFactory.getLogger(WalletTronPrepService.class);
    private static final long ACTIVATION_WAIT_MS = 30_000L;
    /** CatFee covers ENERGY; TRX is still required to burn when free bandwidth is exhausted. */
    private static final long MIN_TRX_FOR_BANDWIDTH_SUN = 1_000_000L;

    private final WalletConfigService configService;
    private final TronGridClient tronGrid;
    private final CatFeeClient catFeeClient;

    public WalletTronPrepService(WalletConfigService configService,
                                   TronGridClient tronGrid,
                                   CatFeeClient catFeeClient) {
        this.configService = configService;
        this.tronGrid = tronGrid;
        this.catFeeClient = catFeeClient;
    }

    /** Prepare HD deposit address before sweeping USDT (ensure TRX + rent energy). */
    public void prepareDepositAddress(String tronAddress) {
        prepareDepositAddress(tronAddress, 0L);
    }

    /** @param knownTrxSun already-fetched TRX balance */
    public void prepareDepositAddress(String tronAddress, long knownTrxSun) {
        ensureMinTrxFromHotWallet(tronAddress, knownTrxSun);
        rentEnergyIfConfigured(tronAddress, "sweep:" + tronAddress);
    }

    /** Prepare hot wallet before broadcasting USDT withdraw. */
    public void prepareHotWallet(String hotWalletAddress) {
        rentEnergyIfConfigured(hotWalletAddress, "withdraw:" + hotWalletAddress);
    }

    /**
     * Ensure deposit address has enough TRX for bandwidth burn.
     * CatFee only rents energy — TRC20 still needs bandwidth (free daily quota or burn TRX).
     * Previously we skipped once the account was activated, leaving dust TRX and causing BANDWITH_ERROR.
     */
    private void ensureMinTrxFromHotWallet(String tronAddress, long knownTrxSun) {
        long minSun = Math.max(configService.getActivationTrxSun(), MIN_TRX_FOR_BANDWIDTH_SUN);
        if (knownTrxSun >= minSun) {
            return;
        }
        if (!configService.isHotWalletConfigured()) {
            throw WalletExceptions.of(HttpStatus.PRECONDITION_FAILED, "HOT_WALLET_NOT_CONFIGURED");
        }
        long amountSun = minSun - Math.max(knownTrxSun, 0L);
        if (amountSun < MIN_TRX_FOR_BANDWIDTH_SUN) {
            amountSun = minSun;
        }
        try {
            String txId = tronGrid.broadcastTrxTransfer(
                configService.getHotWalletPrivateKey().trim(), tronAddress, amountSun);
            log.info("Funded TRON address {} with {} sun from hot wallet tx={} (prevTrxSun={})",
                tronAddress, amountSun, txId, knownTrxSun);
        } catch (IOException e) {
            throw WalletExceptions.of(HttpStatus.BAD_GATEWAY, "ADDRESS_ACTIVATION_FAILED", e.getMessage());
        }
        waitForMinTrx(tronAddress, minSun);
    }

    private void waitForMinTrx(String tronAddress, long minSun) {
        long deadline = System.currentTimeMillis() + ACTIVATION_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            var bal = tronGrid.fetchAccountBalances(tronAddress);
            if (bal.isPresent() && bal.get().trxSun() >= minSun) {
                return;
            }
            if (tronGrid.isAccountActivated(tronAddress) && bal.isPresent() && bal.get().trxSun() > 0) {
                // Activated with partial credit — still wait for min if possible
                if (bal.get().trxSun() >= minSun) {
                    return;
                }
            }
            sleepQuietly(2000L);
        }
        var last = tronGrid.fetchAccountBalances(tronAddress);
        long have = last.map(TronGridClient.AccountBalances::trxSun).orElse(0L);
        if (have < MIN_TRX_FOR_BANDWIDTH_SUN && !tronGrid.isAccountActivated(tronAddress)) {
            throw WalletExceptions.of(HttpStatus.GATEWAY_TIMEOUT, "ADDRESS_ACTIVATION_TIMEOUT",
                "地址激活超时，请稍后重试");
        }
        if (have < MIN_TRX_FOR_BANDWIDTH_SUN) {
            log.warn("Deposit {} TRX still low after top-up haveSun={} wantSun={}",
                tronAddress, have, minSun);
        }
    }

    private void rentEnergyIfConfigured(String receiver, String purpose) {
        if (!catFeeClient.isConfigured()) {
            log.debug("CatFee not configured, skip energy rental for {}", receiver);
            return;
        }
        long need = Math.max(configService.getCatfeeEnergyQuantity(), 1);
        long available = tronGrid.fetchAvailableEnergy(receiver);
        if (available >= need) {
            log.info("Skip CatFee rent for {}: availableEnergy={} >= need={}", receiver, available, need);
            return;
        }
        log.info("CatFee rent needed for {}: availableEnergy={} need={}", receiver, available, need);
        // Avoid ':' in client_order_id — official signing uses raw query (no URL-encoding).
        String clientOrderId = purpose.replace(':', '-') + "-" + System.currentTimeMillis();
        boolean ok = catFeeClient.rentEnergyAndWait(receiver, clientOrderId);
        if (!ok) {
            log.warn("CatFee energy rental failed for {} (check API HTTP/body logs above); fallback to burning TRX if address has any",
                receiver);
        } else {
            log.info("CatFee energy ready for {}", receiver);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
