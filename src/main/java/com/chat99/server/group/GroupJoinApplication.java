package com.chat99.server.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "group_join_application", indexes = {
    @Index(name = "idx_gja_group_status", columnList = "group_id,status,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class GroupJoinApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, length = 128)
    private String groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private GroupJoinApplicationType type;

    @Column(name = "from_user_id", nullable = false, length = 10)
    private String fromUserId;

    /** 邀请入群时的被邀请人；申请加群时与 from_user_id 相同 */
    @Column(name = "to_user_id", nullable = false, length = 10)
    private String toUserId;

    @Column(name = "message", length = 256)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "join_source", length = 32)
    private GroupJoinSource joinSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GroupJoinApplicationStatus status = GroupJoinApplicationStatus.pending;

    @Column(name = "handled_by", length = 10)
    private String handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
