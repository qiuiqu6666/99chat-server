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
@Table(name = "user_conversation_folder")
@IdClass(UserConversationFolderId.class)
@Getter
@Setter
@NoArgsConstructor
public class UserConversationFolder {

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Id
    @Column(name = "folder_id", nullable = false, length = 64)
    private String folderId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** trim + lower，用于同用户分组名唯一（大小写不敏感）。 */
    @Column(name = "name_key", nullable = false, length = 64)
    private String nameKey;

    @Column(name = "scope", nullable = false, length = 16)
    private String scope;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    @Column(name = "created_at", nullable = false)
    private long createdAt;
}
