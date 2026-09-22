package com.chat99.server.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "group_notice_inbox_change")
@Getter
@Setter
@NoArgsConstructor
public class GroupNoticeInboxChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "seq", nullable = false, unique = true)
    private long seq;

    @Column(name = "event_id", nullable = false, length = 64, unique = true)
    private String eventId;

    @Column(name = "item_version", nullable = false)
    private long itemVersion;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "notice_id", length = 256)
    private String noticeId;

    @Column(name = "payload_json", columnDefinition = "JSON")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
        }
    }
}
