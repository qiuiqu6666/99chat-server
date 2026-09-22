package com.chat99.server.push;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserConversationFolderRepository
    extends JpaRepository<UserConversationFolder, UserConversationFolderId> {

    List<UserConversationFolder> findByUserIdOrderByScopeAscSortOrderAscCreatedAtAsc(String userId);

    List<UserConversationFolder> findByUserIdAndScopeOrderBySortOrderAscCreatedAtAsc(
        String userId, String scope);

    Optional<UserConversationFolder> findByUserIdAndNameKey(String userId, String nameKey);

    boolean existsByUserIdAndNameKeyAndFolderIdNot(String userId, String nameKey, String folderId);

    long countByUserIdAndScope(String userId, String scope);

    long countByUserId(String userId);

    void deleteByUserId(String userId);

    void deleteByUserIdAndFolderId(String userId, String folderId);

    @Query("SELECT COALESCE(MAX(f.sortOrder), -1) FROM UserConversationFolder f "
        + "WHERE f.userId = :userId AND f.scope = :scope")
    Optional<Integer> findMaxSortOrder(@Param("userId") String userId, @Param("scope") String scope);

    @Query("SELECT COALESCE(MAX(f.sortOrder), -1) FROM UserConversationFolder f "
        + "WHERE f.userId = :userId")
    Optional<Integer> findMaxSortOrderByUserId(@Param("userId") String userId);
}
