/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.call;

import com.chat99.server.call.CallRecordResult;
import com.chat99.server.call.CallRecordUser;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CallRecordUserRepository
extends JpaRepository<CallRecordUser, Long> {
    public Optional<CallRecordUser> findByCallIdAndUserId(String var1, String var2);

    public Page<CallRecordUser> findByUserIdAndDeletedFalseOrderByOccurredAtDescIdDesc(String var1, Pageable var2);

    public Page<CallRecordUser> findByUserIdAndDeletedFalseAndResultOrderByOccurredAtDescIdDesc(String var1, CallRecordResult var2, Pageable var3);

    @Modifying
    @Transactional
    @Query(value="UPDATE CallRecordUser r SET r.deleted = true, r.updatedAt = :now WHERE r.userId = :userId AND r.deleted = false")
    public int softDeleteAll(@Param(value="userId") String var1, @Param(value="now") Instant var2);

    @Modifying
    @Transactional
    @Query(value="UPDATE CallRecordUser r SET r.deleted = true, r.updatedAt = :now WHERE r.userId = :userId AND r.deleted = false AND r.result = :result")
    public int softDeleteMissed(@Param(value="userId") String var1, @Param(value="result") CallRecordResult var2, @Param(value="now") Instant var3);

    @Query(value="SELECT MAX(r.occurredAt) FROM CallRecordUser r")
    public Optional<Instant> findMaxOccurredAt();
}
