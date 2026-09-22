package com.chat99.server.group;

import java.io.Serializable;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GroupSystemNoticeDismissId implements Serializable {

    private String userId;
    private String noticeId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupSystemNoticeDismissId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(noticeId, that.noticeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, noticeId);
    }
}
