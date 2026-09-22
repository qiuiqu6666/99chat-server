package com.chat99.server.call;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "call_callback_log", indexes = {
    @Index(name = "uk_call_callback_idempotency", columnList = "idempotency_key", unique = true),
    @Index(name = "idx_call_callback_call_id", columnList = "call_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class CallCallbackLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_id", length = 128)
    private String callId;

    @Column(name = "command", length = 32)
    private String command;

    @Column(name = "event_user_id", length = 64)
    private String eventUserId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "processed", nullable = false)
    private boolean processed;

    @Column(name = "process_error", length = 255)
    private String processError;

    @Column(name = "idempotency_key", nullable = false, length = 192, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
