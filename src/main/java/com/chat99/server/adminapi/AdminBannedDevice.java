package com.chat99.server.adminapi;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "admin_banned_device")
@Getter
@Setter
@NoArgsConstructor
public class AdminBannedDevice {

    @Id
    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "banned_by", length = 64)
    private String bannedBy;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "banned_at", nullable = false, updatable = false)
    private Instant bannedAt;

    @PrePersist
    void onCreate() {
        if (bannedAt == null) {
            bannedAt = Instant.now();
        }
    }
}
