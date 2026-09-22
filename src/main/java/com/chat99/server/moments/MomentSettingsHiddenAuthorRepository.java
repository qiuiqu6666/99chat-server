package com.chat99.server.moments;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MomentSettingsHiddenAuthorRepository extends JpaRepository<MomentSettingsHiddenAuthor, Long> {

    List<MomentSettingsHiddenAuthor> findByUserId(String userId);

    @Modifying
    @Query("DELETE FROM MomentSettingsHiddenAuthor h WHERE h.userId = :userId")
    void deleteByUserId(@Param("userId") String userId);
}
