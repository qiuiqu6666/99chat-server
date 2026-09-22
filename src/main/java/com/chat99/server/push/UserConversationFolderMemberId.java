package com.chat99.server.push;

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
public class UserConversationFolderMemberId implements Serializable {

    private String userId;
    private String folderId;
    private String chatType;
    private String peerId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserConversationFolderMemberId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
            && Objects.equals(folderId, that.folderId)
            && Objects.equals(chatType, that.chatType)
            && Objects.equals(peerId, that.peerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, folderId, chatType, peerId);
    }
}
