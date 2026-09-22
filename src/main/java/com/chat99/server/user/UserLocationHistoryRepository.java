package com.chat99.server.user;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserLocationHistoryRepository extends JpaRepository<UserLocationHistory, Long> {

    @Modifying
    @Query("delete from UserLocationHistory h where h.collectedAt < :before")
    int deleteByCollectedAtBefore(@Param("before") Instant before);
}
