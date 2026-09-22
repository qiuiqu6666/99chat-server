package com.chat99.server.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_conversation_folder_member")
@IdClass(UserConversationFolderMemberId.class)
@Getter
@Setter
@NoArgsConstructor
public class UserConversationFolderMember {

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Id
    @Column(name = "folder_id", nullable = false, length = 64)
    private String folderId;

    @Id
    @Column(name = "chat_type", nullable = false, length = 16)
    private String chatType;

    @Id
    @Column(name = "peer_id", nullable = false, length = 128)
    private String peerId;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;
}
