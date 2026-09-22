package com.chat99.server.push;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConversationArchiveRepository
    extends JpaRepository<UserConversationArchive, UserConversationArchiveId> {

    List<UserConversationArchive> findByUserIdOrderByUpdatedAtAsc(String userId);

    List<UserConversationArchive> findByUserIdAndUpdatedAtGreaterThanOrderByUpdatedAtAsc(
        String userId, long updatedAt);
}
