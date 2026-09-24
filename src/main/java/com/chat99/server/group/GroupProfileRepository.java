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

    Page<GroupProfile> findByDismissedFalseAndGroupIdStartingWithOrderByUpdatedAtDesc(
        String prefix, Pageable pageable);

    long countByDismissedTrue();

    @Query("""
        SELECT p.groupId FROM GroupProfile p
        WHERE p.dismissed = false
          AND p.memberCount <> (
            SELECT COUNT(m) FROM GroupMember m
            WHERE m.groupId = p.groupId AND m.deleted = false
          )
        """)
    List<String> findGroupIdsWithMemberCountMismatch(Pageable pageable);

    /**
     * 找 avatar_url 为 NULL 或空字符串的群（GroupAvatarBackfillJob 用）。
     */
    @Query("SELECT g FROM GroupProfile g WHERE g.avatarUrl IS NULL OR g.avatarUrl = \"\"")
    List<GroupProfile> findMissingAvatar(Pageable pageable);
}
