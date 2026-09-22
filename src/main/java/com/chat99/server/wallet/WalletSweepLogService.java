package com.chat99.server.wallet;

import com.chat99.server.common.AppSetting;
import com.chat99.server.common.AppSettingRepository;
import com.chat99.server.telegram.TelegramWalletOpsNotifyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletSweepLogService {

    private static final String KEY_SWEEP_COLLECT_ADDRESS = "wallet.sweep_collect_address";

    private final WalletSweepLogRepository repository;
    private final TelegramWalletOpsNotifyService telegramOpsNotify;
    private final AppSettingRepository appSettingRepository;

    public WalletSweepLogService(WalletSweepLogRepository repository,
                                 TelegramWalletOpsNotifyService telegramOpsNotify,
                                 AppSettingRepository appSettingRepository) {
        this.repository = repository;
        this.telegramOpsNotify = telegramOpsNotify;
        this.appSettingRepository = appSettingRepository;
    }

    @Transactional
    public WalletSweepLog recordSuccess(WalletSweepService.SweepResult result,
                                        String collectAddress,
                                        WalletSweepTrigger trigger,
                                        String operator) {
        String destination = resolveCollectAddress(collectAddress);
        WalletSweepLog row = baseRow(result.userId(), result.fromAddress(), destination, trigger, operator);
        row.setStatus(WalletSweepStatus.SUCCESS);
        row.setUsdtSweptMicro(result.usdtSweptMicro());
        row.setTrxSweptSun(result.trxSweptSun());
        row.setUsdtTxId(result.usdtTxId());
        row.setTrxTxId(result.trxTxId());
        WalletSweepLog saved = repository.save(row);
        telegramOpsNotify.notifySweepSuccess(result, destination, trigger, operator);
        return saved;
    }

    @Transactional
    public WalletSweepLog recordFailure(String userId,
                                        String fromAddress,
                                        String collectAddress,
                                        WalletSweepTrigger trigger,
                                        String operator,
                                        String failReason) {
        WalletSweepLog row = baseRow(userId, fromAddress, resolveCollectAddress(collectAddress), trigger, operator);
        row.setStatus(WalletSweepStatus.FAILED);
        row.setFailReason(trimReason(failReason));
        return repository.save(row);
    }

    private static WalletSweepLog baseRow(String userId,
                                          String fromAddress,
                                          String collectAddress,
                                          WalletSweepTrigger trigger,
                                          String operator) {
        WalletSweepLog row = new WalletSweepLog();
        row.setUserId(userId);
        row.setFromAddress(fromAddress);
        row.setHotWalletAddress(collectAddress);
        row.setTriggerType(trigger);
        row.setOperator(operator);
        return row;
    }

    /** Prefer configured sweep collect address; manual admin API may pass hot wallet by mistake. */
    private String resolveCollectAddress(String fallback) {
        return appSettingRepository.findById(KEY_SWEEP_COLLECT_ADDRESS)
            .map(AppSetting::getValue)
            .filter(v -> v != null && !v.isBlank())
            .map(String::trim)
            .orElse(fallback);
    }

    private static String trimReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 512 ? trimmed : trimmed.substring(0, 512);
    }
}
