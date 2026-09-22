package com.chat99.server.adminapi;

import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.chat99.server.wallet.TronGridClient;
import com.chat99.server.wallet.UserWallet;
import com.chat99.server.wallet.UserWalletRepository;
import com.chat99.server.wallet.WalletChainBalanceService;
import com.chat99.server.wallet.WalletSweepLogService;
import com.chat99.server.wallet.WalletSweepService;
import com.chat99.server.wallet.WalletSweepTrigger;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminWalletTreasuryService {

    private static final int COLLECT_ALL_BATCH = 100;

    private final UserWalletRepository walletRepository;
    private final UserRepository userRepository;
    private final TronGridClient tronGrid;
    private final WalletChainBalanceService chainBalanceService;
    private final WalletSweepService sweepService;
    private final WalletSweepLogService sweepLogService;
    private final AdminAuditService auditService;

    public AdminWalletTreasuryService(UserWalletRepository walletRepository,
                                      UserRepository userRepository,
                                      TronGridClient tronGrid,
                                      WalletChainBalanceService chainBalanceService,
                                      WalletSweepService sweepService,
                                      WalletSweepLogService sweepLogService,
                                      AdminAuditService auditService) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.tronGrid = tronGrid;
        this.chainBalanceService = chainBalanceService;
        this.sweepService = sweepService;
        this.sweepLogService = sweepLogService;
        this.auditService = auditService;
    }

    public TreasurySummaryResponse summary(Authentication auth) {
        AdminAccess.requirePermission(auth, "wallet.read");
        String hotAddress = null;
        String hotUsdt = "0.00";
        String hotTrx = "0.000000";
        if (sweepService.isReady()) {
            hotAddress = sweepService.hotWalletAddress();
            TronGridClient.AccountBalances hotChain = tronGrid.fetchAccountBalances(hotAddress)
                .orElse(new TronGridClient.AccountBalances(0L, 0L));
            hotUsdt = AdminUserFormats.decimalFromMicro(hotChain.usdtMicro());
            hotTrx = AdminUserFormats.decimalFromTrxSun(hotChain.trxSun());
        }
        return new TreasurySummaryResponse(
            AdminUserFormats.decimalFromMicro(walletRepository.sumBalanceUsdtMicro()),
            AdminUserFormats.decimalFromTrxSun(walletRepository.sumBalanceTrxSun()),
            AdminUserFormats.decimalFromMicro(walletRepository.sumChainUsdtMicro()),
            AdminUserFormats.decimalFromTrxSun(walletRepository.sumChainTrxSun()),
            hotAddress,
            hotUsdt,
            hotTrx,
            walletRepository.count(),
            sweepService.isReady());
    }

    @Transactional
    public TreasuryListResponse list(String keyword, int page, int pageSize, boolean refreshChain) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        String kw = blankToNull(keyword);
        Page<UserWallet> result = walletRepository.findAll(buildSpec(kw),
            PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.ASC, "userId")));

        Set<String> userIds = new HashSet<>();
        result.getContent().forEach(w -> userIds.add(w.getUserId()));
        Map<String, String> nicknames = loadNicknames(userIds);

        List<TreasuryWalletItem> items = new ArrayList<>();
        for (UserWallet w : result.getContent()) {
            if (refreshChain) {
                chainBalanceService.refreshAndSave(w);
            }
            items.add(toItem(w, nicknames));
        }

        return new TreasuryListResponse(
            items,
            result.getTotalElements(),
            safePage,
            safeSize,
            safePage * safeSize < result.getTotalElements());
    }

    @Transactional
    public SweepActionResponse collectOne(HttpServletRequest http, Authentication auth, String userUid) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        String collectAddress = sweepService.isReady() ? sweepService.collectAddress() : null;
        UserWallet wallet = walletRepository.findById(userUid).orElse(null);
        String fromAddress = wallet != null ? wallet.getTronAddress() : userUid;
        try {
            WalletSweepService.SweepResult result = sweepService.sweep(userUid, true, true);
            walletRepository.findById(userUid).ifPresent(w -> chainBalanceService.refreshAndSave(w));
            sweepLogService.recordSuccess(result, collectAddress, WalletSweepTrigger.MANUAL, admin.username());
            auditService.log(http, admin.username(), "wallet.treasury.collect", userUid, Map.of(
                "usdt_swept_micro", result.usdtSweptMicro(),
                "trx_swept_sun", result.trxSweptSun(),
                "usdt_tx_id", result.usdtTxId() != null ? result.usdtTxId() : "",
                "trx_tx_id", result.trxTxId() != null ? result.trxTxId() : ""));
            return toSweepResponse(result, "success");
        } catch (Exception e) {
            sweepLogService.recordFailure(userUid, fromAddress, collectAddress,
                WalletSweepTrigger.MANUAL, admin.username(), e.getMessage());
            throw e;
        }
    }

    @Transactional
    public CollectAllResponse collectAll(HttpServletRequest http, Authentication auth) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        List<SweepActionResponse> results = new ArrayList<>();
        int success = 0;
        int failed = 0;
        int skipped = 0;

        String collectAddress = sweepService.isReady() ? sweepService.collectAddress() : null;
        Page<UserWallet> batch = walletRepository.findAll(
            PageRequest.of(0, COLLECT_ALL_BATCH, Sort.by(Sort.Direction.ASC, "userId")));
        for (UserWallet w : batch.getContent()) {
            Optional<TronGridClient.AccountBalances> chainOpt = chainBalanceService.refreshAndSave(w);
            TronGridClient.AccountBalances chain = chainOpt
                .orElse(chainBalanceService.cachedBalances(w));
            if (chain.usdtMicro() < 10_000L && chain.trxSun() <= 2_000_000L) {
                skipped++;
                continue;
            }
            try {
                WalletSweepService.SweepResult result = sweepService.sweep(w.getUserId(), true, true);
                chainBalanceService.refreshAndSave(w);
                sweepLogService.recordSuccess(result, collectAddress, WalletSweepTrigger.COLLECT_ALL, admin.username());
                results.add(toSweepResponse(result, "success"));
                success++;
            } catch (Exception e) {
                failed++;
                sweepLogService.recordFailure(w.getUserId(), w.getTronAddress(), collectAddress,
                    WalletSweepTrigger.COLLECT_ALL, admin.username(), e.getMessage());
                results.add(new SweepActionResponse(
                    w.getUserId(),
                    w.getTronAddress(),
                    "0",
                    "0",
                    null,
                    null,
                    "failed",
                    e.getMessage() != null ? e.getMessage() : "failed"));
            }
        }

        auditService.log(http, admin.username(), "wallet.treasury.collect_all", null, Map.of(
            "success", success,
            "failed", failed,
            "skipped", skipped,
            "batch_size", COLLECT_ALL_BATCH));

        return new CollectAllResponse(success, failed, skipped, results, batch.getTotalElements() > COLLECT_ALL_BATCH);
    }

    private static SweepActionResponse toSweepResponse(WalletSweepService.SweepResult result, String status) {
        return new SweepActionResponse(
            result.userId(),
            result.fromAddress(),
            AdminUserFormats.decimalFromMicro(result.usdtSweptMicro()),
            AdminUserFormats.decimalFromTrxSun(result.trxSweptSun()),
            result.usdtTxId(),
            result.trxTxId(),
            status,
            null);
    }

    private TreasuryWalletItem toItem(UserWallet w, Map<String, String> nicknames) {
        boolean chainLoaded = chainBalanceService.hasCachedChainBalance(w);
        return new TreasuryWalletItem(
            w.getUserId(),
            nicknames.getOrDefault(w.getUserId(), "—"),
            w.getTronAddress(),
            AdminUserFormats.decimalFromMicro(w.getBalanceUsdtMicro()),
            AdminUserFormats.decimalFromTrxSun(w.getBalanceTrxSun()),
            chainLoaded ? AdminUserFormats.decimalFromMicro(w.getChainUsdtMicro()) : null,
            chainLoaded ? AdminUserFormats.decimalFromTrxSun(w.getChainTrxSun()) : null,
            chainLoaded,
            w.getChainBalanceAt() != null ? w.getChainBalanceAt().toString() : null);
    }

    private Specification<UserWallet> buildSpec(String keyword) {
        return (root, query, cb) -> {
            if (keyword == null) {
                return cb.conjunction();
            }
            String like = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.like(cb.lower(root.get("userId")), like));
            preds.add(cb.like(cb.lower(root.get("tronAddress")), like));
            List<User> users = userRepository.findAll((r, q, c) ->
                c.like(c.lower(r.get("nickname")), like));
            if (!users.isEmpty()) {
                preds.add(root.get("userId").in(users.stream().map(User::getUserId).toList()));
            }
            return cb.or(preds.toArray(Predicate[]::new));
        };
    }

    private Map<String, String> loadNicknames(Set<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        userRepository.findByUserIdIn(userIds).forEach(u -> map.put(u.getUserId(), u.getNickname()));
        return map;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TreasurySummaryResponse(
        String platformUsdtTotal,
        String platformTrxTotal,
        String chainUsdtTotal,
        String chainTrxTotal,
        String hotWalletAddress,
        String hotWalletUsdt,
        String hotWalletTrx,
        long walletCount,
        boolean collectReady) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TreasuryListResponse(
        List<TreasuryWalletItem> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TreasuryWalletItem(
        String userUid,
        String nickname,
        String tronAddress,
        String platformUsdt,
        String platformTrx,
        String chainUsdt,
        String chainTrx,
        boolean chainLoaded,
        String chainBalanceAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SweepActionResponse(
        String userUid,
        String tronAddress,
        String usdtSwept,
        String trxSwept,
        String usdtTxId,
        String trxTxId,
        String status,
        String message) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CollectAllResponse(
        int success,
        int failed,
        int skipped,
        List<SweepActionResponse> results,
        boolean hasMore) {}
}
