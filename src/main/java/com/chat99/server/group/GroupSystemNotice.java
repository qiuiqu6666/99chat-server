package com.chat99.server.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "group_system_notice", indexes = {
    @Index(name = "idx_gsn_operator_created", columnList = "operator_user_id,created_at"),
    @Index(name = "idx_gsn_target_created", columnList = "target_user_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class GroupSystemNotice {

    @Id
    @Column(name = "notice_id", length = 256)
    private String noticeId;

    @Column(name = "group_id", nullable = false, length = 128)
    private String groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private GroupSystemNoticeType type;

    @Column(name = "operator_user_id", nullable = false, length = 10)
    private String operatorUserId;

    @Column(name = "target_user_id", nullable = false, length = 10)
    private String targetUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
