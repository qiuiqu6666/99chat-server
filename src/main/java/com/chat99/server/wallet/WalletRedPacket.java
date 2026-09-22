package com.chat99.server.wallet;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_red_packet")
@Getter
@Setter
@NoArgsConstructor
public class WalletRedPacket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 对外字符串 ID（如 IM 卡片 / 客户端路由用），格式 {@code red_packet_{uuid}}。
     * 与数字主键 {@link #id} 等价，接口路径两者均可。
     */
    @Column(name = "public_id", length = 64, unique = true)
    private String publicId;

    @Column(name = "sender_user_id", nullable = false, length = 10)
    private String senderUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "packet_type", nullable = false, length = 32)
    private RedPacketType packetType;

    @Column(name = "conversation_type", nullable = false, length = 8)
    private String conversationType;

    @Column(name = "group_id", length = 64)
    private String groupId;

    @Column(name = "exclusive_user_id", length = 10)
    private String exclusiveUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 16)
    private WalletCurrency currency;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "packet_count", nullable = false)
    private int packetCount;

    @Column(name = "per_amount")
    private Long perAmount;

    @Column(name = "remaining_amount", nullable = false)
    private long remainingAmount;

    @Column(name = "remaining_count", nullable = false)
    private int remainingCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RedPacketStatus status;

    @Column(name = "greeting", length = 128)
    private String greeting;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    /**
     * 与 IM 卡 / 发单入参 {@code toUserId} 对齐（库字段为 exclusive_user_id）。
     * GROUP_TRANSFER / EXCLUSIVE / NORMAL_C2C 有值；群抢红包通常为空。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getToUserId() {
        return exclusiveUserId;
    }

    /**
     * 统一字段命名的收款人用户ID，等同 {@link #exclusiveUserId}；
     * GROUP_TRANSFER 时即群转账收款人（历史列表/详情页统一读取该字段）。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getReceiverUserId() {
        return exclusiveUserId;
    }

    /** 与 IM 发送者 userID 对齐，等同 {@link #senderUserId}。 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getFromUserId() {
        return senderUserId;
    }

    /** 与 IM 卡 {@code amount} 对齐，等同 {@link #totalAmount}（最小单位整数）。 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public long getAmount() {
        return totalAmount;
    }

    /** 与 IM 卡 {@code orderId} 对齐，等同数字主键 {@link #id}。 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public Long getOrderId() {
        return id;
    }

    /** 客户端卡片 ID，等同 {@link #publicId}。 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getClientOrderId() {
        return publicId;
    }

    /**
     * 群红包 / 群转账返回与 IM 会话一致的 groupID；C2C 不返回，避免 conversation mismatch。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String getGroupId() {
        return cardGroupId();
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getChatGroupId() {
        return cardGroupId();
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getImGroupId() {
        return cardGroupId();
    }

    /** GROUP_TRANSFER 时为「群转账」；其它类型省略，客户端仍按红包展示。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getDisplayTitle() {
        return packetType == RedPacketType.GROUP_TRANSFER ? "群转账" : null;
    }

    public String cardGroupId() {
        if (!isGroupConversation()) {
            return null;
        }
        return (groupId == null || groupId.isBlank()) ? null : groupId;
    }

    public boolean isGroupConversation() {
        if (packetType == RedPacketType.GROUP_TRANSFER
            || packetType == RedPacketType.NORMAL_GROUP
            || packetType == RedPacketType.LUCKY_GROUP) {
            return true;
        }
        return conversationType != null && "GROUP".equalsIgnoreCase(conversationType.trim());
    }
}
