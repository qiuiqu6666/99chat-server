package com.chat99.server.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "group_system_notice_dismiss")
@IdClass(GroupSystemNoticeDismissId.class)
@Getter
@Setter
@NoArgsConstructor
public class GroupSystemNoticeDismiss {

    @Id
    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Id
    @Column(name = "notice_id", nullable = false, length = 256)
    private String noticeId;

    @Column(name = "dismissed_at", nullable = false)
    private Instant dismissedAt;

    public GroupSystemNoticeDismiss(String userId, String noticeId) {
        this.userId = userId;
        this.noticeId = noticeId;
    }

    @PrePersist
    void onCreate() {
        if (dismissedAt == null) {
            dismissedAt = Instant.now();
        }
    }
}
