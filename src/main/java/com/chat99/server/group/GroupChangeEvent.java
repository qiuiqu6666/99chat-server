package com.chat99.server.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "group_change_event")
@Getter
@Setter
@NoArgsConstructor
public class GroupChangeEvent {

    @Id
    @Column(name = "change_event_id", length = 64)
    private String changeEventId;

    @Column(name = "group_id", nullable = false, length = 128)
    private String groupId;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "operator_user_id", length = 32)
    private String operatorUserId;

    @Column(name = "member_user_ids", columnDefinition = "JSON")
    private String memberUserIdsJson;

    @Column(name = "occurred_at", nullable = false)
    private long occurredAt;

    @Column(name = "timeline_rank")
    private Integer timelineRank;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "detail", columnDefinition = "JSON")
    private String detailJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private long createdAt;

    /** 全局单调序号；历史行可为 null。 */
    @Column(name = "group_seq")
    private Long groupSeq;

    /** 单条实体的修订号（每次写 +1）。 */
    @Column(name = "item_version", nullable = false)
    private long itemVersion;

    /** 域全局单调 revision（snapshotRevision 一致性）。 */
    @Column(name = "revision", nullable = false)
    private long revision;

    @PrePersist
    void onCreate() {
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
        }
    }
}
