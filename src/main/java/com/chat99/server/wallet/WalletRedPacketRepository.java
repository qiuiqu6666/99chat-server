/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.wallet;

import com.chat99.server.wallet.RedPacketStatus;
import com.chat99.server.wallet.WalletRedPacket;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRedPacketRepository
extends JpaRepository<WalletRedPacket, Long>,
JpaSpecificationExecutor<WalletRedPacket> {
    public Optional<WalletRedPacket> findByPublicId(String var1);

    public boolean existsByPublicId(String var1);

    @Lock(value=LockModeType.PESSIMISTIC_WRITE)
    @Query(value="SELECT p FROM WalletRedPacket p WHERE p.id = :id")
    public Optional<WalletRedPacket> findByIdForUpdate(@Param(value="id") long var1);

    @Lock(value=LockModeType.PESSIMISTIC_WRITE)
    @Query(value="SELECT p FROM WalletRedPacket p WHERE p.publicId = :publicId")
    public Optional<WalletRedPacket> findByPublicIdForUpdate(@Param(value="publicId") String var1);

    @Query(value="SELECT p FROM WalletRedPacket p\nWHERE p.status = :status\n  AND p.expiresAt IS NOT NULL\n  AND p.expiresAt < :now\n")
    public List<WalletRedPacket> findActiveExpiredPackets(@Param(value="status") RedPacketStatus var1, @Param(value="now") Instant var2);

    public Page<WalletRedPacket> findBySenderUserIdOrderByCreatedAtDesc(String var1, Pageable var2);

    @Query(value="SELECT COALESCE(SUM(p.totalAmount), 0)\nFROM WalletRedPacket p\nWHERE p.createdAt >= :start AND p.createdAt < :end\n")
    public long sumTotalAmountBetween(@Param(value="start") Instant var1, @Param(value="end") Instant var2);

    @Query(value="SELECT COUNT(DISTINCT p.groupId)\nFROM WalletRedPacket p\nWHERE p.groupId IS NOT NULL AND p.groupId <> ''\n")
    public long countDistinctGroupIds();
}
