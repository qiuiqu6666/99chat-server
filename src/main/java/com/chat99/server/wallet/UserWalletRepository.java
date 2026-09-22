package com.chat99.server.wallet;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserWalletRepository extends JpaRepository<UserWallet, String>, JpaSpecificationExecutor<UserWallet> {

    Optional<UserWallet> findByTronAddress(String tronAddress);

    Optional<UserWallet> findByDerivationIndex(long derivationIndex);

    @Query("""
        SELECT w FROM UserWallet w
        WHERE w.tronPrivateKey IS NULL OR w.tronPrivateKey = ''
        ORDER BY w.derivationIndex ASC
        """)
    List<UserWallet> findMissingPrivateKey();

    @Query("SELECT w.tronAddress FROM UserWallet w")
    List<String> findAllTronAddresses();

    List<UserWallet> findByDerivationIndexGreaterThanOrderByDerivationIndexAsc(
        long derivationIndex, Pageable pageable);

    @Query(value = """
        SELECT w.* FROM user_wallet w
        WHERE w.derivation_index > :derivationIndex
          AND EXISTS (
            SELECT 1 FROM users u
            WHERE u.user_id COLLATE utf8mb4_unicode_ci = w.user_id COLLATE utf8mb4_unicode_ci
              AND u.last_active_at IS NOT NULL
          )
        ORDER BY w.derivation_index ASC
        """, nativeQuery = true)
    List<UserWallet> findEligibleForDepositScanAfterDerivationIndex(
        @Param("derivationIndex") long derivationIndex, Pageable pageable);

    @Query(value = """
        SELECT w.* FROM user_wallet w
        WHERE EXISTS (
            SELECT 1 FROM users u
            WHERE u.user_id COLLATE utf8mb4_unicode_ci = w.user_id COLLATE utf8mb4_unicode_ci
              AND u.last_active_at IS NOT NULL
          )
        ORDER BY w.user_id ASC
        """, nativeQuery = true)
    List<UserWallet> findAllEligibleForDepositScan();

    @Query("SELECT COALESCE(SUM(w.balanceUsdtMicro), 0) FROM UserWallet w")
    long sumBalanceUsdtMicro();

    @Query("SELECT COALESCE(SUM(w.balancePlatformFen), 0) FROM UserWallet w")
    long sumBalancePlatformFen();

    @Query("SELECT COALESCE(SUM(w.balanceTrxSun), 0) FROM UserWallet w")
    long sumBalanceTrxSun();

    @Query("SELECT COALESCE(SUM(w.chainUsdtMicro), 0) FROM UserWallet w WHERE w.chainBalanceAt IS NOT NULL")
    long sumChainUsdtMicro();

    @Query("SELECT COALESCE(SUM(w.chainTrxSun), 0) FROM UserWallet w WHERE w.chainBalanceAt IS NOT NULL")
    long sumChainTrxSun();

    @Query("""
        SELECT w FROM UserWallet w
        ORDER BY w.chainUsdtMicro DESC, w.userId ASC
        """)
    List<UserWallet> findSweepBatch(Pageable pageable);

    @Query("""
        SELECT COALESCE(SUM(w.amountMicro + w.feeMicro), 0)
        FROM WalletWithdrawal w
        WHERE w.status IN :statuses
        """)
    long sumPendingWithdrawMicro(@Param("statuses") List<WithdrawalStatus> statuses);
}
