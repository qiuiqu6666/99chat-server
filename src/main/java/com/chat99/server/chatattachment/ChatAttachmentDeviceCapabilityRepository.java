package com.chat99.server.chatattachment;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatAttachmentDeviceCapabilityRepository
    extends JpaRepository<ChatAttachmentDeviceCapability, ChatAttachmentDeviceCapabilityId> {

    List<ChatAttachmentDeviceCapability> findByUserIdAndDeviceIdIn(String userId, Collection<String> deviceIds);
}
