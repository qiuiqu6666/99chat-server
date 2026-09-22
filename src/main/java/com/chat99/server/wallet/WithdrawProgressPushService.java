package com.chat99.server.wallet;

import com.chat99.server.push.ApnsLiveActivityPushSender;
import com.chat99.server.push.JpushPushSender;
import com.chat99.server.push.PushPlatform;
import com.chat99.server.push.PushProvider;
import com.chat99.server.push.PushSendResult;
import com.chat99.server.push.UserPushToken;
import com.chat99.server.push.UserPushTokenRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class WithdrawProgressPushService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawProgressPushService.class);
    private static final String EVENT_END = WithdrawProgressPushPayload.EVENT_END;
    private static final String EVENT_UPDATE = WithdrawProgressPushPayload.EVENT_UPDATE;
    private static final int SEND_ATTEMPTS = 3;

    private final WalletWithdrawalRepository withdrawalRepository;
    private final WalletWithdrawLiveActivityRepository liveActivityRepository;
    private final UserPushTokenRepository pushTokenRepository;
    private final WalletConfigService configService;
    private final ApnsLiveActivityPushSender liveActivityPushSender;
    private final JpushPushSender jpushPushSender;
    private final long throttleMs;
    private final int dismissalSeconds;

    public WithdrawProgressPushService(WalletWithdrawalRepository withdrawalRepository,
                                       WalletWithdrawLiveActivityRepository liveActivityRepository,
                                       UserPushTokenRepository pushTokenRepository,
                                       WalletConfigService configService,
                                       ApnsLiveActivityPushSender liveActivityPushSender,
                                       JpushPushSender jpushPushSender,
                                       @Value("${chat99.wallet.withdraw-progress-push-throttle-ms:5000}")
                                       long throttleMs,
                                       @Value("${chat99.wallet.withdraw-live-activity-dismissal-seconds:0}")
                                       int dismissalSeconds) {
        this.withdrawalRepository = withdrawalRepository;
        this.liveActivityRepository = liveActivityRepository;
        this.pushTokenRepository = pushTokenRepository;
        this.configService = configService;
        this.liveActivityPushSender = liveActivityPushSender;
        this.jpushPushSender = jpushPushSender;
        this.throttleMs = throttleMs > 0 ? throttleMs : 5000L;
        this.dismissalSeconds = Math.max(0, dismissalSeconds);
    }

    public void notifyAfterCommit(WalletWithdrawal w) {
        if (w == null || w.getId() == null) {
            return;
        }
        long id = w.getId();
        runAfterCommit(() -> notifyNow(id));
    }

    public void notifyNow(long withdrawalId) {
        WalletWithdrawal w = withdrawalRepository.findById(withdrawalId).orElse(null);
        if (w == null) {
            return;
        }
        var rowOpt = liveActivityRepository.findByWithdrawalId(withdrawalId);
        if (rowOpt.isEmpty()) {
            return;
        }
        WalletWithdrawLiveActivity row = rowOpt.get();
        WithdrawStage stage = WithdrawStage.fromStatus(w.getStatus());
        int required = requiredConfirmations();
        boolean terminal = WithdrawProgressPushPayload.terminal(stage);
        if (!shouldPush(row, stage, w.getConfirmations(), Instant.now(), throttleMs, terminal)) {
            return;
        }
        boolean iosBound = isIos(row);
        boolean iosOk = sendIosWithRetry(row, w, required, terminal);
        sendAndroidData(w, required);
        if (liveActivityRepository.findByWithdrawalId(withdrawalId).isEmpty()) {
            return;
        }
        row = liveActivityRepository.findByWithdrawalId(withdrawalId).orElse(null);
        if (row == null) {
            return;
        }
        if (terminal && (!iosBound || iosOk)) {
            liveActivityRepository.delete(row);
            return;
        }
        row.setLastPushAt(Instant.now());
        row.setLastPushStage(stage.name());
        row.setLastPushConfirmations(w.getConfirmations());
        if (!terminal) {
            row.setLastPushEvent(EVENT_UPDATE);
        }
        liveActivityRepository.save(row);
    }

    /** 终态 push 失败时由确认扫描补推，避免 activity 残留。 */
    public void retryUnfinishedTerminalPushes() {
        for (WalletWithdrawLiveActivity row : liveActivityRepository.findNotEnded()) {
            WalletWithdrawal w = withdrawalRepository.findById(row.getWithdrawalId()).orElse(null);
            if (w == null) {
                liveActivityRepository.delete(row);
                continue;
            }
            if (!WithdrawProgressPushPayload.terminal(WithdrawStage.fromStatus(w.getStatus()))) {
                continue;
            }
            notifyNow(w.getId());
        }
    }

    static boolean shouldPush(WalletWithdrawLiveActivity row, WithdrawStage stage, int confirmations,
                              Instant now, long throttleMs, boolean terminal) {
        if (row == null || stage == null) {
            return false;
        }
        if (EVENT_END.equals(row.getLastPushEvent()) && !terminal) {
            return false;
        }
        if (terminal) {
            return true;
        }
        String lastStage = row.getLastPushStage();
        if (lastStage == null || !lastStage.equals(stage.name())) {
            return true;
        }
        if (confirmations <= row.getLastPushConfirmations()) {
            return false;
        }
        Instant last = row.getLastPushAt();
        if (last == null) {
            return true;
        }
        return !now.isBefore(last.plusMillis(Math.max(0, throttleMs)));
    }

    private boolean sendIosWithRetry(WalletWithdrawLiveActivity row, WalletWithdrawal w,
                                     int required, boolean terminal) {
        if (!isIos(row) || !liveActivityPushSender.isReady()) {
            return !isIos(row);
        }
        String payload;
        try {
            payload = WithdrawProgressPushPayload.apnsPayload(w, required, terminal, dismissalSeconds);
        } catch (Exception e) {
            log.warn("live-activity payload failed withdrawalId={} err={}", w.getId(), e.getMessage());
            return false;
        }
        String collapseId = ApnsLiveActivityPushSender.collapseId(String.valueOf(w.getId()));
        PushSendResult last = PushSendResult.failed("NOT_ATTEMPTED");
        for (int i = 0; i < SEND_ATTEMPTS; i++) {
            last = liveActivityPushSender.send(
                row.getPushToken(), row.getBundleId(), row.getEnvironment(),
                payload, collapseId, terminal);
            if (last.sent() || last.invalidToken()) {
                break;
            }
        }
        if (last.invalidToken()) {
            log.warn("live-activity token expired, clearing withdrawalId={}", w.getId());
            liveActivityRepository.delete(row);
            return false;
        }
        if (!last.sent()) {
            log.warn("live-activity push failed withdrawalId={} detail={}", w.getId(), last.detail());
        }
        return last.sent();
    }

    private void sendAndroidData(WalletWithdrawal w, int required) {
        if (!jpushPushSender.isReady()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (UserPushToken token : pushTokenRepository.findByUserIdAndApnsEnabledTrue(w.getUserId())) {
            if (token.getProvider() == PushProvider.JPUSH
                && token.getPlatform() == PushPlatform.ANDROID
                && token.getPushToken() != null
                && !token.getPushToken().isBlank()) {
                ids.add(token.getPushToken());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        PushSendResult result = jpushPushSender.sendDataMessage(
            ids, WithdrawProgressPushPayload.androidData(w, required), w.getUserId());
        if (!result.sent() && !result.invalidToken()) {
            log.warn("withdraw android data push failed userId={} withdrawalId={} detail={}",
                w.getUserId(), w.getId(), result.detail());
        }
    }

    private static boolean isIos(WalletWithdrawLiveActivity row) {
        String p = row.getPlatform();
        return p == null || p.isBlank() || "ios".equalsIgnoreCase(p.trim());
    }

    private int requiredConfirmations() {
        int n = configService.getDepositConfirmations();
        return n > 0 ? n : 19;
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
