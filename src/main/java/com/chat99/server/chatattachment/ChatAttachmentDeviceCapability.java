package com.chat99.server.chatattachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_attachment_device_capability")
@IdClass(ChatAttachmentDeviceCapabilityId.class)
@Getter
@Setter
@NoArgsConstructor
public class ChatAttachmentDeviceCapability {

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Id
    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "platform", length = 16)
    private String platform;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "app_version_code")
    private Integer appVersionCode;

    @Column(name = "protocol_version")
    private Integer protocolVersion;

    @Column(name = "capable", nullable = false)
    private boolean capable;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
