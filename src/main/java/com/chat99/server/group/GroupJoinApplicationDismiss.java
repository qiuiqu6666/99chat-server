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
@Table(name = "group_join_application_dismiss")
@IdClass(GroupJoinApplicationDismissId.class)
@Getter
@Setter
@NoArgsConstructor
public class GroupJoinApplicationDismiss {

    @Id
    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Id
    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "dismissed_at", nullable = false)
    private Instant dismissedAt;

    public GroupJoinApplicationDismiss(String userId, Long applicationId) {
        this.userId = userId;
        this.applicationId = applicationId;
    }

    @PrePersist
    void onCreate() {
        if (dismissedAt == null) {
            dismissedAt = Instant.now();
        }
    }
}
