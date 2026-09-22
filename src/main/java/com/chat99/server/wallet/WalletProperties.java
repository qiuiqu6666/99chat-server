package com.chat99.server.wallet;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.wallet")
public record WalletProperties(
    String depositMnemonic,
    String hotWalletPrivateKey,
    String trongridApiKey,
    String trongridBaseUrl,
    String usdtContract,
    long minDepositUsdtMicro,
    int depositConfirmations,
    long depositScanIntervalMs,
    long depositConfirmScanIntervalMs,
    long depositHotScanIntervalMs,
    long depositColdScanIntervalMs,
    int depositHotTtlMinutes,
    int depositColdBatchSize,
    int depositColdLookbackDays,
    long depositScanMaxRoundMs,
    int depositScanConcurrency,
    int trongridQpsLimit,
    String depositMode,
    long depositBlockScanIntervalMs,
    int depositBlockScanBatchSize,
    int redPacketExpireHours,
    long redPacketExpireScanIntervalMs,
    long withdrawBroadcastIntervalMs,
    String frankfurterUrl,
    int exchangeRateCacheSeconds,
    int payPinMaxFailures,
    int payPinLockMinutes,
    String catfeeApiKey,
    String catfeeApiSecret,
    String catfeeBaseUrl,
    int catfeeEnergyQuantity,
    String catfeeEnergyDuration,
    long catfeeOrderTimeoutMs,
    long activationTrxSun,
    String withdrawMode,
    boolean sweepJobEnabled,
    long sweepJobIntervalMs,
    int sweepJobBatchSize,
    long sweepJobMinUsdtMicro,
    String sweepCollectAddress) {

    public boolean depositMnemonicConfigured() {
        return depositMnemonic != null && !depositMnemonic.isBlank();
    }

    public boolean hotWalletConfigured() {
        return hotWalletPrivateKey != null && !hotWalletPrivateKey.isBlank();
    }

    public boolean blockScanMode() {
        return "block-scan".equalsIgnoreCase(depositMode);
    }

    public boolean addressPollMode() {
        return depositMode == null || depositMode.isBlank() || "address-poll".equalsIgnoreCase(depositMode);
    }

    public boolean catfeeConfigured() {
        return catfeeApiKey != null && !catfeeApiKey.isBlank()
            && catfeeApiSecret != null && !catfeeApiSecret.isBlank();
    }

    public boolean withdrawAutoMode() {
        return withdrawMode != null && "auto".equalsIgnoreCase(withdrawMode);
    }
}
