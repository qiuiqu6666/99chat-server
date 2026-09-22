package com.chat99.server.chatattachment;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

public class ChatUserDailyQuotaId implements Serializable {

    private String userId;
    private LocalDate quotaDay;

    public ChatUserDailyQuotaId() {}

    public ChatUserDailyQuotaId(String userId, LocalDate quotaDay) {
        this.userId = userId;
        this.quotaDay = quotaDay;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public LocalDate getQuotaDay() {
        return quotaDay;
    }

    public void setQuotaDay(LocalDate quotaDay) {
        this.quotaDay = quotaDay;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatUserDailyQuotaId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(quotaDay, that.quotaDay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, quotaDay);
    }
}
