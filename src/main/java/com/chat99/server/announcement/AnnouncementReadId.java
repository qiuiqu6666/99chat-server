package com.chat99.server.announcement;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class AnnouncementReadId implements Serializable {

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "announcement_id", nullable = false, length = 36)
    private String announcementId;

    public AnnouncementReadId(String userId, String announcementId) {
        this.userId = userId;
        this.announcementId = announcementId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AnnouncementReadId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(announcementId, that.announcementId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, announcementId);
    }
}
