package com.chat99.server.wallet;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.WalletCardNotSubmittedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Leases are committed before network I/O; stale workers cannot overwrite a receipt. */
@Service
public class WalletCardDelivery {
    private static final Logger log = LoggerFactory.getLogger(WalletCardDelivery.class);
    private final WalletCardOutboxRepository outbox;
    private final ImAdminClient im;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "wallet-card-delivery"); thread.setDaemon(true); return thread;
        });

    public WalletCardDelivery(WalletCardOutboxRepository outbox, ImAdminClient im,
        ObjectMapper json, PlatformTransactionManager manager) {
        this.outbox = outbox; this.im = im; this.json = json; this.tx = new TransactionTemplate(manager);
    }

    @Scheduled(scheduler = "walletCardScheduler", initialDelay = 5000,
        fixedDelayString = "${wallet.card-delivery.poll-ms:2000}")
    public void dispatch() {
        try {
            for (String id : outbox.due(Instant.now(), PageRequest.of(0, 8))) {
                try {
                    workers.execute(() -> {
                        try { deliver(id); } catch (RuntimeException e) { log.warn("wallet card worker failed id={}", id, e); }
                    });
                } catch (RejectedExecutionException full) { break; } // Remains durable and eligible for the next poll.
            }
        } catch (RuntimeException e) { log.warn("wallet card poll failed", e); }
    }

    @PreDestroy public void shutdown() { workers.shutdown(); }

    /** Synchronous drain for maintenance/tests; production uses bounded dispatch. */
    public void run() {
        try {
            for (String id : outbox.due(Instant.now(), PageRequest.of(0, 8))) {
                try { deliver(id); } catch (RuntimeException e) { log.warn("wallet card worker failed id={}", id, e); }
            }
        } catch (RuntimeException e) { log.warn("wallet card poll failed", e); }
    }

    public void deliver(String id) {
        WalletCardOutbox row = tx.execute(status -> claim(id, Instant.now()));
        if (row == null) return;
        if ("RECONCILING".equals(row.getState())) { reconcile(row); return; }
        try {
            var receipt = im.sendWalletCard(row.isGroupMessage(), row.getSenderId(), row.getTargetId(),
                row.getImRandom(), row.getFirstAttemptAt().getEpochSecond(), row.getPayload());
            if (receipt == null || !receipt.accepted()) {
                finish(row, false, null, null, "RESULT_UNKNOWN", true);
            } else finish(row, true, receipt.msgKey(), receipt.msgSeq(), null, false);
        } catch (WalletCardNotSubmittedException e) {
            finish(row, false, null, null, "NOT_SUBMITTED", false);
        } catch (ImRestException e) {
            boolean uncertain = e.imErrorCode() == 0 || e.imErrorCode() == 10002
                || e.imErrorCode() == 20004 || e.imErrorCode() == 20005;
            finish(row, false, null, null, "IM_" + e.imErrorCode(), uncertain);
        } catch (RuntimeException e) {
            finish(row, false, null, null, "RESULT_UNKNOWN", true);
        }
    }

    private WalletCardOutbox claim(String id, Instant now) {
        var row = outbox.lock(id).orElse(null);
        if (row == null || !("PENDING".equals(row.getState()) || "SENDING".equals(row.getState())
            || "REVIEW".equals(row.getState()) || "RECONCILING".equals(row.getState()))
            || row.getNextAttemptAt().isAfter(now)) return null;
        // A PENDING row means there is positive evidence that the previous attempt failed.
        // Lease expiry means the process may have died after IM accepted: reconcile it.
        boolean send = "PENDING".equals(row.getState());
        if (send) {
            row.setFirstAttemptAt(now); row.setReconcileCursor(null);
            row.setAttempts(row.getAttempts() + 1);
        }
        row.setState(send ? "SENDING" : "RECONCILING");
        row.setLeaseToken(UUID.randomUUID().toString()); row.setNextAttemptAt(now.plusSeconds(90));
        return row;
    }

    private void reconcile(WalletCardOutbox claimed) {
        WalletCardHistory.Page page = null;
        String error = null;
        try {
            if (claimed.getFirstAttemptAt() == null) throw new IllegalStateException("Missing attempt time");
            var history = im.walletCardHistory(claimed.isGroupMessage(), claimed.getSenderId(),
                claimed.getTargetId(), claimed.getFirstAttemptAt().getEpochSecond(), claimed.getReconcileCursor());
            page = WalletCardHistory.inspect(claimed, history, json);
        } catch (RuntimeException e) { error = "HISTORY_UNAVAILABLE"; }
        final var result = page;
        final String failure = error;
        tx.executeWithoutResult(status -> {
            var row = outbox.lock(claimed.getId()).orElseThrow();
            if (!claimed.getLeaseToken().equals(row.getLeaseToken()) || "SENT".equals(row.getState())) return;
            if (result != null && result.found()) {
                row.setState("SENT"); row.setMessageKey(result.key()); row.setMessageSeq(result.seq());
                row.setReconcileCursor(null); row.setLastError(null); row.setNextSyncAt(Instant.now());
            } else {
                row.setState("RECONCILING");
                if (result != null) row.setReconcileCursor(result.nextCursor());
                row.setLastError(failure != null ? failure : "DELIVERY_UNCONFIRMED");
                row.setNextAttemptAt(Instant.now().plusSeconds(result != null && result.nextCursor() != null ? 2 : 60));
            }
        });
    }

    private void finish(WalletCardOutbox sent, boolean success, String key, Long seq, String error, boolean uncertain) {
        tx.executeWithoutResult(status -> {
            var row = outbox.lock(sent.getId()).orElseThrow();
            if (!sent.getLeaseToken().equals(row.getLeaseToken())) return;
            // A callback can win the race but lack a locator returned by REST.
            if (success) {
                if (key != null && !key.isBlank()) row.setMessageKey(key);
                if (seq != null && seq > 0) row.setMessageSeq(seq);
            }
            if ("SENT".equals(row.getState())) return;
            boolean located = row.isGroupMessage() ? row.getMessageSeq() != null && row.getMessageSeq() > 0
                : row.getMessageKey() != null && !row.getMessageKey().isBlank();
            if (success && !located) { row.setState("RECONCILING"); row.setLastError("MESSAGE_LOCATOR_MISSING"); return; }
            boolean review = !success && uncertain;
            row.setState(success ? "SENT" : review ? "RECONCILING" : "PENDING");
            row.setLastError(error);
            if (review) log.warn("wallet card reconciling id={} reason={}", row.getId(), error);
            row.setNextAttemptAt(Instant.now().plusSeconds(Math.min(30, 2L << Math.min(4, row.getAttempts()))));
        });
    }

    /** Call ONLY after callback authentication; match sender, target, order and original random. */
    public void receipt(String command, String raw) {
        try {
            Map<String, Object> body = json.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            String cmd = command == null || command.isBlank() ? String.valueOf(body.get("CallbackCommand")) : command;
            boolean group = "Group.CallbackAfterSendMsg".equals(cmd);
            if (!group && !"C2C.CallbackAfterSendMsg".equals(cmd)) return;
            if (body.get("SendMsgResult") instanceof Number result && result.intValue() != 0) return;
            for (var card : WalletOrderCardImSupport.extractCards(body, json)) {
                String prefix = WalletOrderCardImSupport.TYPE_TRANSFER.equals(card.customType()) ? "transfer:" : "rp:";
                String id = prefix + card.orderIdRaw();
                tx.executeWithoutResult(status -> {
                    var row = outbox.lock(id).orElse(null);
                    Object random = body.get(group ? "Random" : "MsgRandom");
                    if (row == null || row.getFirstAttemptAt() == null || row.isGroupMessage() != group
                        || !row.getSenderId().equals(body.get("From_Account"))
                        || !row.getTargetId().equals(body.get(group ? "GroupId" : "To_Account"))
                        || !(random instanceof Number n) || n.intValue() != row.getImRandom()) return;
                    if (body.get("MsgKey") != null) row.setMessageKey(body.get("MsgKey").toString());
                    if (body.get("MsgSeq") instanceof Number seq && seq.longValue() > 0)
                        row.setMessageSeq(seq.longValue());
                    boolean located = group ? row.getMessageSeq() != null && row.getMessageSeq() > 0
                        : row.getMessageKey() != null && !row.getMessageKey().isBlank();
                    row.setState(located ? "SENT" : "RECONCILING");
                    row.setLastError(located ? null : "MESSAGE_LOCATOR_MISSING");
                });
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid IM callback", e);
        }
    }
}
