/*
 * Copyright (c) 2024 chat99-server
 * 群成员 Repository：所有读方法默认过滤 deleted=false（tombstone 行不参与业务），
 * 写方法走软删（deleted=1 + deleted_at + item_version++）。
 */
package com.chat99.server.group;

import com.chat99.server.group.GroupMember;
import com.chat99.server.group.GroupMemberId;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    @Query("SELECT e FROM GroupMember e JOIN GroupProfile p ON p.groupId = e.groupId "
        + "WHERE e.userId = :userId AND p.dismissed = false AND e.deleted = false "
        + "ORDER BY p.groupName ASC, e.groupId ASC")
    Page<GroupMember> findMyGroups(@Param("userId") String var1, Pageable var2);

    @Query("SELECT COUNT(e) FROM GroupMember e JOIN GroupProfile p ON p.groupId = e.groupId "
        + "WHERE e.userId = :userId AND p.dismissed = false AND e.deleted = false")
    long countMyGroups(@Param("userId") String user1);

    @Query("SELECT e.userId, COUNT(e) FROM GroupMember e JOIN GroupProfile p ON p.groupId = e.groupId "
        + "WHERE e.userId IN :userIds AND p.dismissed = false AND e.deleted = false "
        + "GROUP BY e.userId")
    List<Object[]> countMyGroupsByUserIds(@Param("userIds") Collection<String> userIds);

    @Query("SELECT COUNT(e) FROM GroupMember e JOIN GroupProfile p ON p.groupId = e.groupId "
        + "WHERE e.userId = :userId AND p.dismissed = false AND e.deleted = false "
        + "AND LOWER(p.groupType) = 'community'")
    long countCommunityGroupsByUserId(@Param("userId") String userId);

    @Query("SELECT COUNT(e) FROM GroupMember e JOIN GroupProfile p ON p.groupId = e.groupId "
        + "WHERE e.userId = :userId AND p.dismissed = false AND e.deleted = false "
        + "AND (p.groupType IS NULL OR LOWER(p.groupType) <> 'community')")
    long countNonCommunityGroupsByUserId(@Param("userId") String userId);

    @Query("SELECT e.userId, COUNT(e) FROM GroupMember e JOIN GroupProfile p ON p.groupId = e.groupId "
        + "WHERE e.userId IN :userIds AND p.dismissed = false AND e.deleted = false "
        + "AND LOWER(p.groupType) = 'community' GROUP BY e.userId")
    List<Object[]> countCommunityGroupsByUserIds(@Param("userIds") Collection<String> userIds);

    @Query("SELECT e.userId, COUNT(e) FROM GroupMember e JOIN GroupProfile p ON p.groupId = e.groupId "
        + "WHERE e.userId IN :userIds AND p.dismissed = false AND e.deleted = false "
        + "AND (p.groupType IS NULL OR LOWER(p.groupType) <> 'community') GROUP BY e.userId")
    List<Object[]> countNonCommunityGroupsByUserIds(@Param("userIds") Collection<String> userIds);

    @Query("SELECT e.groupId FROM GroupMember e JOIN GroupProfile p ON p.groupId = e.groupId "
        + "WHERE e.userId = :userId AND p.dismissed = false AND e.deleted = false")
    List<String> findActiveGroupIdsByUserId(@Param("userId") String userId);

    /** 读群成员行（含已 tombstone 的，admin/snapshot 用），排除已解散群。 */
    @Query("SELECT e.userId FROM GroupMember e JOIN GroupProfile p ON p.groupId = e.groupId "
        + "WHERE e.groupId = :groupId AND p.dismissed = false")
    List<String> findUserIdsByGroupId(@Param("groupId") String groupId);

    /** 读群成员行（含已 tombstone 的，admin/audit 用） */
    @Query("SELECT e.userId FROM GroupMember e WHERE e.groupId = :groupId AND e.role >= :minRole")
    List<String> findUserIdsByGroupIdAndMinRole(
        @Param("groupId") String groupId, @Param("minRole") int minRole);

    /** admin/snapshot 读（不过滤 deleted），按 minRole 过滤 */
    @Query("SELECT e FROM GroupMember e WHERE e.groupId = :groupId AND e.role >= :minRole")
    List<GroupMember> findByGroupIdAndRoleGreaterThanEqual(
        @Param("groupId") String groupId, @Param("minRole") int minRole);

    @Query("SELECT e FROM GroupMember e WHERE e.groupId = :groupId AND e.userId = :userId AND e.deleted = false")
    Optional<GroupMember> findByGroupIdAndUserIdActive(
        @Param("groupId") String groupId, @Param("userId") String userId);

    /** 老方法（兼容保留）：未过滤 deleted。admin/快照用途。 */
    @Query("SELECT COUNT(e) FROM GroupMember e WHERE e.groupId = :groupId")
    long countByGroupId(@Param("groupId") String groupId);

    /** 业务路径用的真活成员计数：排除 tombstone 行（deleted=true）。 */
    @Query("SELECT COUNT(e) FROM GroupMember e WHERE e.groupId = :groupId AND e.deleted = false")
    long countActiveByGroupId(@Param("groupId") String groupId);

    @Query("SELECT e FROM GroupMember e WHERE e.userId = :userId AND e.groupId IN :groupIds AND e.deleted = false")
    List<GroupMember> findByUserIdAndGroupIdIn(
        @Param("userId") String userId,
        @Param("groupIds") java.util.Set<String> groupIds);

    @Query("SELECT e.userId FROM GroupMember e WHERE e.groupId = :groupId AND e.deleted = false AND e.role >= :minRole")
    List<String> findUserIdsByGroupIdAndRoleAtLeast(
        @Param("groupId") String groupId, @Param("minRole") int minRole);

    @Query("""
        SELECT e FROM GroupMember e
        WHERE e.groupId = :groupId
          AND e.mutedUntil IS NOT NULL
          AND e.mutedUntil > :nowSec
          AND e.deleted = false
        ORDER BY e.mutedUntil ASC, e.userId ASC
        """)
    List<GroupMember> findActivelyMutedByGroupId(@Param("groupId") String groupId, @Param("nowSec") long nowSec);

    @Query("""
        SELECT m1 FROM GroupMember m1 INNER JOIN GroupMember m2
          ON m2.groupId = m1.groupId AND m2.userId = :peerUserId
        WHERE m1.userId = :userId AND m1.deleted = false AND m2.deleted = false
        """)
    Page<GroupMember> findCommonGroups(
        @Param("userId") String userId,
        @Param("peerUserId") String peerUserId,
        Pageable pageable);

    @Query("""
        SELECT COUNT(m1) FROM GroupMember m1 INNER JOIN GroupMember m2
          ON m2.groupId = m1.groupId AND m2.userId = :peerUserId
        WHERE m1.userId = :userId AND m1.deleted = false AND m2.deleted = false
        """)
    long countCommonGroups(@Param("userId") String userId, @Param("peerUserId") String peerUserId);

    /** 群成员展示顺序固定为：群主、管理员、普通成员；同身份按入群时间和用户 ID 稳定排序。 */
    @Query("SELECT e FROM GroupMember e WHERE e.groupId = :groupId AND e.deleted = false "
        + "ORDER BY e.role DESC, e.joinedAt ASC, e.userId ASC")
    List<GroupMember> findListByGroupId(@Param("groupId") String groupId, Pageable pageable);

    @Query("SELECT e FROM GroupMember e WHERE e.groupId = :groupId AND e.deleted = false "
        + "AND e.role >= :minRole ORDER BY e.role DESC, e.joinedAt ASC, e.userId ASC")
    List<GroupMember> findListByGroupIdAndRoleAtLeast(
        @Param("groupId") String groupId, @Param("minRole") int minRole, Pageable pageable);

    @Query("SELECT COUNT(e) FROM GroupMember e WHERE e.groupId = :groupId AND e.deleted = false "
        + "AND e.role >= :minRole")
    long countActiveByGroupIdAndRoleAtLeast(
        @Param("groupId") String groupId, @Param("minRole") int minRole);

    @Query("SELECT e FROM GroupMember e WHERE e.groupId = :groupId AND e.deleted = false "
        + "AND e.role < :maxRole ORDER BY e.role DESC, e.joinedAt ASC, e.userId ASC")
    List<GroupMember> findListByGroupIdAndRoleBelow(
        @Param("groupId") String groupId, @Param("maxRole") int maxRoleExclusive, Pageable pageable);

    @Query("SELECT COUNT(e) FROM GroupMember e WHERE e.groupId = :groupId AND e.deleted = false "
        + "AND e.role < :maxRole")
    long countActiveByGroupIdAndRoleBelow(
        @Param("groupId") String groupId, @Param("maxRole") int maxRoleExclusive);

    /** 软删全群成员 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value="UPDATE GroupMember m SET m.deleted = true, m.deletedAt = CURRENT_TIMESTAMP, m.itemVersion = m.itemVersion + 1, m.updatedAt = CURRENT_TIMESTAMP "
        + "WHERE m.groupId = :groupId AND m.deleted = false")
    public int bulkSoftDeleteByGroupId(@Param("groupId") String groupId);

    /** 软删单成员 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value="UPDATE GroupMember m SET m.deleted = true, m.deletedAt = CURRENT_TIMESTAMP, m.itemVersion = m.itemVersion + 1, m.updatedAt = CURRENT_TIMESTAMP "
        + "WHERE m.groupId = :groupId AND m.userId = :userId AND m.deleted = false")
    public int bulkSoftDeleteByGroupIdAndUserId(@Param("groupId") String groupId, @Param("userId") String userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE GroupMember m SET m.deleted = false, m.deletedAt = NULL, m.itemVersion = m.itemVersion + 1, m.updatedAt = CURRENT_TIMESTAMP "
        + "WHERE m.groupId = :groupId AND m.userId = :userId AND m.deleted = true")
    public int revive(@Param("groupId") String groupId, @Param("userId") String userId);
}
