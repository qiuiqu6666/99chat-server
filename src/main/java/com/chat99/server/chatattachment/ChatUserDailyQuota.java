package com.chat99.server.chatattachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_user_daily_quota")
@IdClass(ChatUserDailyQuotaId.class)
@Getter
@Setter
@NoArgsConstructor
public class ChatUserDailyQuota {

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Id
    @Column(name = "quota_day", nullable = false)
    private LocalDate quotaDay;

    @Column(name = "used_bytes", nullable = false)
    private long usedBytes;

    @Column(name = "reserved_bytes", nullable = false)
    private long reservedBytes;
}
