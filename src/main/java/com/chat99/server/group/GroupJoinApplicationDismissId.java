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
public class GroupJoinApplicationDismissId implements Serializable {

    private String userId;
    private Long applicationId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupJoinApplicationDismissId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(applicationId, that.applicationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, applicationId);
    }
}
