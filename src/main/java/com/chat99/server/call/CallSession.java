package com.chat99.server.call;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "call_session")
@Getter
@Setter
@NoArgsConstructor
public class CallSession {

    @Id
    @Column(name = "call_id", length = 128)
    private String callId;

    @Column(name = "room_id", length = 128)
    private String roomId;

    @Column(name = "call_type", length = 16)
    private String callType;

    @Column(name = "media_type", length = 16)
    private String mediaType;

    @Column(name = "caller_user_id", length = 10)
    private String callerUserId;

    @Column(name = "callee_user_id", length = 10)
    private String calleeUserId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /** 最后一条 av_call 信令时间（用于超时补全，避免用 DB updated_at 拉长通话时长）。 */
    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private CallSessionStatus status;

    @Column(name = "raw_payload_json", columnDefinition = "TEXT")
    private String rawPayloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
