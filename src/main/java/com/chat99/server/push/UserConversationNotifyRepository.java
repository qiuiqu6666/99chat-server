package com.chat99.server.push;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserConversationNotifyRepository extends JpaRepository<UserConversationNotify, Long> {

    Optional<UserConversationNotify> findByUserIdAndChatTypeAndPeerId(String userId, String chatType, String peerId);

    @Query("SELECT n FROM UserConversationNotify n WHERE n.userId = :userId "
        + "AND n.chatType IN :chatTypes AND n.peerId IN :peerIds")
    List<UserConversationNotify> findByUserIdAndChatTypeInAndPeerIdIn(
        @Param("userId") String userId,
        @Param("chatTypes") Collection<String> chatTypes,
        @Param("peerIds") Collection<String> peerIds);

    @Query("SELECT n.userId FROM UserConversationNotify n WHERE n.chatType = :chatType AND n.peerId = :peerId "
        + "AND n.muted = true AND n.userId IN :userIds")
    Set<String> findMutedUserIds(@Param("chatType") String chatType,
                                 @Param("peerId") String peerId,
                                 @Param("userIds") Collection<String> userIds);

    List<UserConversationNotify> findByUserIdAndMutedTrue(String userId);
}
