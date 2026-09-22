package com.chat99.server.push;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConversationPinRepository
    extends JpaRepository<UserConversationPin, UserConversationPinId> {

    List<UserConversationPin> findByUserIdOrderByPinnedAtDesc(String userId);

    long countByUserId(String userId);
}
