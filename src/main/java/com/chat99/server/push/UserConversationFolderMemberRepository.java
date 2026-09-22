package com.chat99.server.push;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserConversationFolderMemberRepository
    extends JpaRepository<UserConversationFolderMember, UserConversationFolderMemberId> {

    List<UserConversationFolderMember> findByUserId(String userId);

    List<UserConversationFolderMember> findByUserIdAndFolderId(String userId, String folderId);

    void deleteByUserId(String userId);

    void deleteByUserIdAndFolderId(String userId, String folderId);

    void deleteByUserIdAndChatTypeAndPeerId(String userId, String chatType, String peerId);

    long countByUserIdAndChatTypeAndPeerId(String userId, String chatType, String peerId);

    long countByUserIdAndFolderId(String userId, String folderId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
        DELETE FROM UserConversationFolderMember m
        WHERE m.userId = :userId
          AND m.chatType = :chatType
          AND m.peerId = :peerId
          AND m.folderId <> :folderId
        """)
    int deleteByUserIdAndChatTypeAndPeerIdAndFolderIdNot(
        @Param("userId") String userId,
        @Param("chatType") String chatType,
        @Param("peerId") String peerId,
        @Param("folderId") String folderId);
}
