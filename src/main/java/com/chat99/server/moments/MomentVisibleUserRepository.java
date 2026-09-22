package com.chat99.server.moments;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MomentVisibleUserRepository extends JpaRepository<MomentVisibleUser, Long> {

    List<MomentVisibleUser> findByMomentId(String momentId);

    List<MomentVisibleUser> findByMomentIdIn(Collection<String> momentIds);

    @Modifying
    @Query("DELETE FROM MomentVisibleUser v WHERE v.momentId = :momentId")
    void deleteByMomentId(@Param("momentId") String momentId);
}
