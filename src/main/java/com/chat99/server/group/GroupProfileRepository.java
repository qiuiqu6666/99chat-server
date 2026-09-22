package com.chat99.server.group;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface GroupProfileRepository extends JpaRepository<GroupProfile, String>,
    JpaSpecificationExecutor<GroupProfile> {

    List<GroupProfile> findByGroupIdEndingWith(String suffix);

    Page<GroupProfile> findByDismissedTrue(Pageable pageable);

    long countByDismissedTrue();

    /**
     * 找 avatar_url 为 NULL 或空字符串的群（GroupAvatarBackfillJob 用）。
     */
    @Query("SELECT g FROM GroupProfile g WHERE g.avatarUrl IS NULL OR g.avatarUrl = \"\"")
    List<GroupProfile> findMissingAvatar(Pageable pageable);
}
