/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.wallet;

import com.chat99.server.common.AppSetting;
import com.chat99.server.common.AppSettingRepository;
import com.chat99.server.wallet.WalletProperties;
import com.chat99.server.wallet.WalletSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WalletConfigService {
    private static final Logger log = LoggerFactory.getLogger(WalletConfigService.class);
    private static final String KEY_DEPOSIT_MNEMONIC = "wallet.deposit_mnemonic";
    private static final String KEY_HOT_WALLET_PRIVATE_KEY = "wallet.hot_wallet_private_key";
    private static final String KEY_TRONGRID_API_KEY = "wallet.trongrid_api_key";
    private static final String KEY_TRONGRID_BASE_URL = "wallet.trongrid_base_url";
    private static final String KEY_USDT_CONTRACT = "wallet.usdt_contract";
    private static final String KEY_DEPOSIT_MODE = "wallet.deposit_mode";
    private static final String KEY_MIN_DEPOSIT_USDT_MICRO = "wallet.min_deposit_usdt_micro";
    private static final String KEY_DEPOSIT_CONFIRMATIONS = "wallet.deposit_confirmations";
    private static final String KEY_DEPOSIT_HOT_TTL_MINUTES = "wallet.deposit_hot_ttl_minutes";
    private static final String KEY_TRONGRID_QPS_LIMIT = "wallet.trongrid_qps_limit";
    private static final String KEY_DEPOSIT_SCAN_CONCURRENCY = "wallet.deposit_scan_concurrency";
    private static final String KEY_DEPOSIT_BLOCK_SCAN_BATCH_SIZE = "wallet.deposit_block_scan_batch_size";
    private static final String KEY_PAY_PIN_MAX_FAILURES = "wallet.pay_pin_max_failures";
    private static final String KEY_PAY_PIN_LOCK_MINUTES = "wallet.pay_pin_lock_minutes";
    private static final String KEY_RED_PACKET_EXPIRE_HOURS = "wallet.red_packet_expire_hours";
    private static final String KEY_EXCHANGE_RATE_CACHE_SECONDS = "wallet.exchange_rate_cache_seconds";
    private static final String KEY_FRANKFURTER_URL = "wallet.frankfurter_url";
    private static final String KEY_DEPOSIT_COLD_BATCH_SIZE = "wallet.deposit_cold_batch_size";
    private static final String KEY_DEPOSIT_COLD_LOOKBACK_DAYS = "wallet.deposit_cold_lookback_days";
    private static final String KEY_DEPOSIT_SCAN_MAX_ROUND_MS = "wallet.deposit_scan_max_round_ms";
    private static final String KEY_CATFEE_API_KEY = "wallet.catfee_api_key";
    private static final String KEY_CATFEE_API_SECRET = "wallet.catfee_api_secret";
    private static final String KEY_CATFEE_BASE_URL = "wallet.catfee_base_url";
    private static final String KEY_CATFEE_ENERGY_QUANTITY = "wallet.catfee_energy_quantity";
    private static final String KEY_CATFEE_ENERGY_DURATION = "wallet.catfee_energy_duration";
    private static final String KEY_CATFEE_ORDER_TIMEOUT_MS = "wallet.catfee_order_timeout_ms";
    private static final String KEY_ACTIVATION_TRX_SUN = "wallet.activation_trx_sun";
    private static final String KEY_WITHDRAW_MODE = "wallet.withdraw_mode";
    private static final String KEY_SWEEP_JOB_ENABLED = "wallet.sweep_job_enabled";
    private static final String KEY_SWEEP_JOB_INTERVAL_MS = "wallet.sweep_job_interval_ms";
    private static final String KEY_SWEEP_JOB_BATCH_SIZE = "wallet.sweep_job_batch_size";
    private static final String KEY_SWEEP_JOB_MIN_USDT_MICRO = "wallet.sweep_job_min_usdt_micro";
    private final AppSettingRepository appSettingRepository;
    private final WalletProperties props;
    private final WalletSecretCipher secretCipher;

    public WalletConfigService(AppSettingRepository appSettingRepository, WalletProperties props, WalletSecretCipher secretCipher) {
        this.appSettingRepository = appSettingRepository;
        this.props = props;
        this.secretCipher = secretCipher;
    }

    public String getDepositMnemonic() {
        return this.secretCipher.open(this.getOrDefault(KEY_DEPOSIT_MNEMONIC, this.props.depositMnemonic()));
    }

    public String getHotWalletPrivateKey() {
        return this.secretCipher.open(this.getOrDefault(KEY_HOT_WALLET_PRIVATE_KEY, this.props.hotWalletPrivateKey()));
    }

    public String getTrongridApiKey() {
        return this.getOrDefault(KEY_TRONGRID_API_KEY, this.props.trongridApiKey());
    }

    /** Comma-separated keys are supported for round-robin API usage. */
    public java.util.List<String> getTrongridApiKeys() {
        return java.util.Arrays.stream(getTrongridApiKey() == null ? new String[0] : getTrongridApiKey().split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    public String getTrongridBaseUrl() {
        return this.getOrDefault(KEY_TRONGRID_BASE_URL, this.props.trongridBaseUrl());
    }

    public String getUsdtContract() {
        return this.getOrDefault(KEY_USDT_CONTRACT, this.props.usdtContract());
    }

    public String getDepositMode() {
        return this.getOrDefault(KEY_DEPOSIT_MODE, this.props.depositMode());
    }

    public long getMinDepositUsdtMicro() {
        return this.getLongOrDefault(KEY_MIN_DEPOSIT_USDT_MICRO, this.props.minDepositUsdtMicro());
    }

    public int getDepositConfirmations() {
        return this.getIntOrDefault(KEY_DEPOSIT_CONFIRMATIONS, this.props.depositConfirmations());
    }

    public int getDepositHotTtlMinutes() {
        return this.getIntOrDefault(KEY_DEPOSIT_HOT_TTL_MINUTES, this.props.depositHotTtlMinutes());
    }

    public int getTrongridQpsLimit() {
        return this.getIntOrDefault(KEY_TRONGRID_QPS_LIMIT, this.props.trongridQpsLimit());
    }

    public int getDepositScanConcurrency() {
        return this.getIntOrDefault(KEY_DEPOSIT_SCAN_CONCURRENCY, this.props.depositScanConcurrency());
    }

    public int getDepositBlockScanBatchSize() {
        return this.getIntOrDefault(KEY_DEPOSIT_BLOCK_SCAN_BATCH_SIZE, this.props.depositBlockScanBatchSize());
    }

    public int getPayPinMaxFailures() {
        return this.getIntOrDefault(KEY_PAY_PIN_MAX_FAILURES, this.props.payPinMaxFailures());
    }

    public int getPayPinLockMinutes() {
        return this.getIntOrDefault(KEY_PAY_PIN_LOCK_MINUTES, this.props.payPinLockMinutes());
    }

    public int getRedPacketExpireHours() {
        return this.getIntOrDefault(KEY_RED_PACKET_EXPIRE_HOURS, this.props.redPacketExpireHours());
    }

    public int getExchangeRateCacheSeconds() {
        return this.getIntOrDefault(KEY_EXCHANGE_RATE_CACHE_SECONDS, this.props.exchangeRateCacheSeconds());
    }

    public String getFrankfurterUrl() {
        return this.getOrDefault(KEY_FRANKFURTER_URL, this.props.frankfurterUrl());
    }

    public int getDepositColdBatchSize() {
        return this.getIntOrDefault(KEY_DEPOSIT_COLD_BATCH_SIZE, this.props.depositColdBatchSize());
    }

    public int getDepositColdLookbackDays() {
        return this.getIntOrDefault(KEY_DEPOSIT_COLD_LOOKBACK_DAYS, this.props.depositColdLookbackDays());
    }

    public long getDepositScanMaxRoundMs() {
        return this.getLongOrDefault(KEY_DEPOSIT_SCAN_MAX_ROUND_MS, this.props.depositScanMaxRoundMs());
    }

    public String getCatfeeApiKey() {
        return this.getOrDefault(KEY_CATFEE_API_KEY, this.props.catfeeApiKey());
    }

    public String getCatfeeApiSecret() {
        return this.getOrDefault(KEY_CATFEE_API_SECRET, this.props.catfeeApiSecret());
    }

    public String getCatfeeBaseUrl() {
        return this.getOrDefault(KEY_CATFEE_BASE_URL, this.props.catfeeBaseUrl());
    }

    public int getCatfeeEnergyQuantity() {
        return this.getIntOrDefault(KEY_CATFEE_ENERGY_QUANTITY, this.props.catfeeEnergyQuantity());
    }

    public String getCatfeeEnergyDuration() {
        return this.getOrDefault(KEY_CATFEE_ENERGY_DURATION, this.props.catfeeEnergyDuration());
    }

    public long getCatfeeOrderTimeoutMs() {
        return this.getLongOrDefault(KEY_CATFEE_ORDER_TIMEOUT_MS, this.props.catfeeOrderTimeoutMs());
    }

    public long getActivationTrxSun() {
        return this.getLongOrDefault(KEY_ACTIVATION_TRX_SUN, this.props.activationTrxSun());
    }

    public String getWithdrawMode() {
        return this.getOrDefault(KEY_WITHDRAW_MODE, this.props.withdrawMode());
    }

    public boolean isWithdrawAutoMode() {
        String mode = this.getWithdrawMode();
        if (mode == null || mode.isBlank()) {
            return this.props.withdrawAutoMode();
        }
        return "auto".equalsIgnoreCase(mode);
    }

    public boolean isSweepJobEnabled() {
        String val = this.getOrDefault(KEY_SWEEP_JOB_ENABLED, String.valueOf(this.props.sweepJobEnabled()));
        return !"false".equalsIgnoreCase(val) && !"0".equals(val);
    }

    public long getSweepJobIntervalMs() {
        return this.getLongOrDefault(KEY_SWEEP_JOB_INTERVAL_MS, this.props.sweepJobIntervalMs());
    }

    public int getSweepJobBatchSize() {
        return this.getIntOrDefault(KEY_SWEEP_JOB_BATCH_SIZE, this.props.sweepJobBatchSize());
    }

    public long getSweepJobMinUsdtMicro() {
        return this.getLongOrDefault(KEY_SWEEP_JOB_MIN_USDT_MICRO, this.props.sweepJobMinUsdtMicro());
    }

    public boolean isCatfeeConfigured() {
        String key = this.getCatfeeApiKey();
        String secret = this.getCatfeeApiSecret();
        return key != null && !key.isBlank() && secret != null && !secret.isBlank();
    }

    public boolean isDepositMnemonicConfigured() {
        String mnemonic = this.getDepositMnemonic();
        return mnemonic != null && !mnemonic.isBlank();
    }

    public boolean isHotWalletConfigured() {
        String key = this.getHotWalletPrivateKey();
        return key != null && !key.isBlank();
    }

    public boolean isBlockScanMode() {
        return "block-scan".equalsIgnoreCase(this.getDepositMode());
    }

    public boolean isAddressPollMode() {
        String mode = this.getDepositMode();
        return mode == null || mode.isBlank() || "address-poll".equalsIgnoreCase(mode);
    }

    public void setDepositMnemonic(String value) {
        this.setValue(KEY_DEPOSIT_MNEMONIC, this.sealIfPresent(value));
    }

    public void setHotWalletPrivateKey(String value) {
        this.setValue(KEY_HOT_WALLET_PRIVATE_KEY, this.sealIfPresent(value));
    }

    private String sealIfPresent(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return this.secretCipher.seal(value);
    }

    public void setTrongridApiKey(String value) {
        this.setValue(KEY_TRONGRID_API_KEY, value);
    }

    public void setTrongridBaseUrl(String value) {
        this.setValue(KEY_TRONGRID_BASE_URL, value);
    }

    public void setUsdtContract(String value) {
        this.setValue(KEY_USDT_CONTRACT, value);
    }

    public void setDepositMode(String value) {
        this.setValue(KEY_DEPOSIT_MODE, value);
    }

    public void setMinDepositUsdtMicro(long value) {
        this.setValue(KEY_MIN_DEPOSIT_USDT_MICRO, String.valueOf(value));
    }

    public void setDepositConfirmations(int value) {
        this.setValue(KEY_DEPOSIT_CONFIRMATIONS, String.valueOf(value));
    }

    public void setDepositHotTtlMinutes(int value) {
        this.setValue(KEY_DEPOSIT_HOT_TTL_MINUTES, String.valueOf(value));
    }

    public void setTrongridQpsLimit(int value) {
        this.setValue(KEY_TRONGRID_QPS_LIMIT, String.valueOf(value));
    }

    public void setDepositScanConcurrency(int value) {
        this.setValue(KEY_DEPOSIT_SCAN_CONCURRENCY, String.valueOf(value));
    }

    public void setDepositBlockScanBatchSize(int value) {
        this.setValue(KEY_DEPOSIT_BLOCK_SCAN_BATCH_SIZE, String.valueOf(value));
    }

    public void setPayPinMaxFailures(int value) {
        this.setValue(KEY_PAY_PIN_MAX_FAILURES, String.valueOf(value));
    }

    public void setPayPinLockMinutes(int value) {
        this.setValue(KEY_PAY_PIN_LOCK_MINUTES, String.valueOf(value));
    }

    public void setRedPacketExpireHours(int value) {
        this.setValue(KEY_RED_PACKET_EXPIRE_HOURS, String.valueOf(value));
    }

    public void setExchangeRateCacheSeconds(int value) {
        this.setValue(KEY_EXCHANGE_RATE_CACHE_SECONDS, String.valueOf(value));
    }

    public void setFrankfurterUrl(String value) {
        this.setValue(KEY_FRANKFURTER_URL, value);
    }

    public void setDepositColdBatchSize(int value) {
        this.setValue(KEY_DEPOSIT_COLD_BATCH_SIZE, String.valueOf(value));
    }

    public void setDepositColdLookbackDays(int value) {
        this.setValue(KEY_DEPOSIT_COLD_LOOKBACK_DAYS, String.valueOf(value));
    }

    public void setDepositScanMaxRoundMs(long value) {
        this.setValue(KEY_DEPOSIT_SCAN_MAX_ROUND_MS, String.valueOf(value));
    }

    private String getOrDefault(String key, String defaultValue) {
        return this.appSettingRepository.findById(key).map(setting -> {
            String val = setting.getValue();
            return val != null && !val.isBlank() ? val : defaultValue;
        }).orElse(defaultValue);
    }

    private long getLongOrDefault(String key, long defaultValue) {
        String val = this.getOrDefault(key, null);
        if (val == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(val);
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int getIntOrDefault(String key, int defaultValue) {
        String val = this.getOrDefault(key, null);
        if (val == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val);
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void setValue(String key, String value) {
        if (value == null) {
            this.appSettingRepository.deleteById(key);
        } else {
            this.appSettingRepository.findById(key).ifPresentOrElse(setting -> {
                setting.setValue(value);
                this.appSettingRepository.save(setting);
            }, () -> {
                AppSetting setting = new AppSetting(key, value);
                this.appSettingRepository.save(setting);
            });
        }
        log.info("Wallet config updated: {} = [{}]", (Object)key, (Object)(value != null ? "***" : "null"));
    }
}
