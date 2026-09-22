package com.chat99.server.wallet;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long>,
    JpaSpecificationExecutor<WalletLedger> {

    Page<WalletLedger> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    Page<WalletLedger> findByUserIdAndLedgerTypeInOrderByCreatedAtDesc(
        String userId, Collection<WalletLedgerType> ledgerTypes, Pageable pageable);

    /** 链上充提：按 ledgerType + refType 双重过滤（refType 取 'DEPOSIT'/'WITHDRAW'，排除运营调账与退款）。 */
    Page<WalletLedger> findByUserIdAndLedgerTypeInAndRefTypeInOrderByCreatedAtDesc(
        String userId, Collection<WalletLedgerType> ledgerTypes,
        Collection<String> refTypes, Pageable pageable);

    /** 运营后台调账：新数据 refType='ADMIN_ADJUST'，历史数据 ledgerType=ADMIN_ADJUST（按 amount 符号归方向）。 */
    @Query("""
        SELECT l FROM WalletLedger l WHERE l.userId = :userId AND (
            (l.refType = 'ADMIN_ADJUST' AND l.ledgerType IN :types)
            OR (l.ledgerType = :adminAdjustType AND (
                (:wantDeposit = true AND l.amount >= 0)
                OR (:wantWithdraw = true AND l.amount < 0)
            ))
        ) ORDER BY l.createdAt DESC""")
    Page<WalletLedger> findInternalAdjustLedger(
        @Param("userId") String userId,
        @Param("types") Collection<WalletLedgerType> types,
        @Param("adminAdjustType") WalletLedgerType adminAdjustType,
        @Param("wantDeposit") boolean wantDeposit,
        @Param("wantWithdraw") boolean wantWithdraw,
        Pageable pageable);

    /** 钱包总记录页 ledger 拉取：闪兑/退款类型 + 运营调账 + 提现失败退款。 */
    @Query("""
        SELECT l FROM WalletLedger l WHERE l.userId = :userId AND (
            l.ledgerType IN :types
            OR l.refType = 'ADMIN_ADJUST'
            OR l.ledgerType = :adminAdjustType
            OR (:includeWithdrawRefund = true AND l.ledgerType = :withdrawType AND l.refType = 'WITHDRAW_REFUND')
        ) ORDER BY l.createdAt DESC""")
    Page<WalletLedger> findForWalletRecordLedger(
        @Param("userId") String userId,
        @Param("types") Collection<WalletLedgerType> types,
        @Param("adminAdjustType") WalletLedgerType adminAdjustType,
        @Param("withdrawType") WalletLedgerType withdrawType,
        @Param("includeWithdrawRefund") boolean includeWithdrawRefund,
        Pageable pageable);

    @Query("""
        SELECT l FROM WalletLedger l WHERE l.userId = :userId
        AND (l.refType = 'ADMIN_ADJUST' OR l.ledgerType = :adminAdjustType)
        ORDER BY l.createdAt DESC""")
    List<WalletLedger> findAdminAdjustLedgers(
        @Param("userId") String userId,
        @Param("adminAdjustType") WalletLedgerType adminAdjustType);

    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM WalletLedger l WHERE l.userId = :userId "
        + "AND l.currency = :currency AND l.ledgerType = :type AND l.createdAt >= :since")
    long sumAmountSince(@Param("userId") String userId,
                        @Param("currency") WalletCurrency currency,
                        @Param("type") WalletLedgerType type,
                        @Param("since") java.time.Instant since);

    List<WalletLedger> findByRefTypeAndRefIdOrderByCreatedAtAsc(String refType, Long refId);

    Optional<WalletLedger> findFirstByUserIdAndLedgerTypeAndRemarkOrderByIdAsc(
        String userId, WalletLedgerType ledgerType, String remark);
}
