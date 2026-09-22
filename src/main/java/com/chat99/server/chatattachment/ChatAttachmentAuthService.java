package com.chat99.server.chatattachment;

import com.chat99.server.group.GroupAccessService;
import com.chat99.server.group.GroupMember;
import com.chat99.server.group.GroupMemberRepository;
import com.chat99.server.group.GroupProfile;
import com.chat99.server.group.GroupProfileRepository;
import com.chat99.server.group.GroupProjectionService;
import com.chat99.server.user.UserFriendService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatAttachmentAuthService {

    private final ChatAttachmentProperties props;
    private final UserFriendService friendService;
    private final GroupAccessService groupAccessService;
    private final GroupProfileRepository groupProfileRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupProjectionService groupProjectionService;
    private final ChatAttachmentCapabilityService capabilityService;

    public ChatAttachmentAuthService(ChatAttachmentProperties props,
                                     UserFriendService friendService,
                                     GroupAccessService groupAccessService,
                                     GroupProfileRepository groupProfileRepository,
                                     GroupMemberRepository groupMemberRepository,
                                     GroupProjectionService groupProjectionService,
                                     ChatAttachmentCapabilityService capabilityService) {
        this.props = props;
        this.friendService = friendService;
        this.groupAccessService = groupAccessService;
        this.groupProfileRepository = groupProfileRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupProjectionService = groupProjectionService;
        this.capabilityService = capabilityService;
    }

    public void requireCanUpload(boolean existingSession, boolean callerCapable) {
        if (props.emergencyDisabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ATTACHMENT_DISABLED");
        }
        if (!existingSession && !props.uploadEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ATTACHMENT_DISABLED");
        }
        if (!existingSession && !callerCapable) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ATTACHMENT_DISABLED");
        }
    }

    public void requireCanSend(boolean callerCapable) {
        if (props.emergencyDisabled() || !props.sendEnabled() || !callerCapable) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ATTACHMENT_DISABLED");
        }
    }

    public void requireCanRead() {
        if (props.emergencyDisabled() || !props.readEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ATTACHMENT_DISABLED");
        }
    }

    public void requireConversationPermission(String userId,
                                              ChatAttachmentConversationIds.ConversationIdentity conv) {
        if (conv.type() == ChatConversationType.c2c) {
            String peer = peerOf(userId, conv);
            if (!friendService.isMutualActive(userId, peer)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONVERSATION_FORBIDDEN");
            }
            return;
        }
        requireGroupSendable(userId, conv.groupId());
    }

    public void requireSendConversation(String userId,
                                        ChatAttachmentConversationIds.ConversationIdentity conv) {
        requireConversationPermission(userId, conv);
        requireSenderAllowlisted(userId);
        if (conv.type() == ChatConversationType.c2c) {
            capabilityService.requirePeerDevicesCapable(peerOf(userId, conv));
            return;
        }
        if (!props.gray().groupAllowed(conv.groupId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONVERSATION_FORBIDDEN");
        }
        requireGroupMembersCapable(conv.groupId());
    }

    public void requireGroupMember(String groupId, String userId) {
        requireGroupNotDismissed(groupId);
        if (!groupAccessService.isMember(groupId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
    }

    public boolean isC2cParticipant(String userId, ChatAttachmentReference ref) {
        return userId != null && (userId.equals(ref.getParticipantLow()) || userId.equals(ref.getParticipantHigh()));
    }

    public String peerOf(String userId, ChatAttachmentConversationIds.ConversationIdentity conv) {
        if (userId.equals(conv.participantLow())) {
            return conv.participantHigh();
        }
        if (userId.equals(conv.participantHigh())) {
            return conv.participantLow();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
    }

    private void requireSenderAllowlisted(String userId) {
        if (!props.gray().senderAllowed(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONVERSATION_FORBIDDEN");
        }
    }

    private void requireGroupSendable(String userId, String groupId) {
        requireGroupNotDismissed(groupId);
        String role = groupAccessService.requireMemberRole(groupId, userId);
        if (groupProjectionService.isShutUpAll(groupId)
            && !"Owner".equals(role) && !"Admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONVERSATION_FORBIDDEN");
        }
    }

    private void requireGroupNotDismissed(String groupId) {
        GroupProfile profile = groupProfileRepository.findById(groupId).orElse(null);
        if (profile != null && profile.isDismissed()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
    }

    private void requireGroupMembersCapable(String groupId) {
        if (!props.gray().requirePeerCapability()) {
            return;
        }
        List<GroupMember> members = groupMemberRepository.findListByGroupId(groupId,
            org.springframework.data.domain.Pageable.unpaged());
        for (GroupMember member : members) {
            capabilityService.requirePeerDevicesCapable(member.getUserId());
        }
    }
}
