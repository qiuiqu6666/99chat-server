package com.chat99.server.announcement;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "announcement_read")
@Getter
@Setter
@NoArgsConstructor
public class AnnouncementRead {

    @EmbeddedId
    private AnnouncementReadId id;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    public AnnouncementRead(String userId, String announcementId) {
        this.id = new AnnouncementReadId(userId, announcementId);
    }

    @PrePersist
    void onCreate() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }
}
