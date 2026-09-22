package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.chat99.server.group.GroupAccessService;
import com.chat99.server.group.GroupMemberRepository;
import com.chat99.server.group.GroupProfile;
import com.chat99.server.group.GroupProfileRepository;
import com.chat99.server.group.GroupProjectionService;
import com.chat99.server.user.UserFriendService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ChatAttachmentAuthServiceTest {

    @Mock UserFriendService friendService;
    @Mock GroupAccessService groupAccessService;
    @Mock GroupProfileRepository groupProfileRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock GroupProjectionService groupProjectionService;
    @Mock ChatAttachmentCapabilityService capabilityService;

    private ChatAttachmentAuthService service;

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentAuthService(
            ChatAttachmentTestSupport.props(), friendService, groupAccessService,
            groupProfileRepository, groupMemberRepository, groupProjectionService, capabilityService);
    }

    @Test
    void c2cSendRequiresFriendship() {
        when(friendService.isMutualActive("u1", "u2")).thenReturn(false);
        var conv = ChatAttachmentConversationIds.c2c("u1", "u2");
        assertThatThrownBy(() -> service.requireConversationPermission("u1", conv))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("CONVERSATION_FORBIDDEN");
    }

    @Test
    void dismissedGroupDenied() {
        GroupProfile profile = new GroupProfile();
        profile.setGroupId("g1");
        profile.setDismissed(true);
        when(groupProfileRepository.findById("g1")).thenReturn(Optional.of(profile));
        assertThatThrownBy(() -> service.requireGroupMember("g1", "u1"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void c2cParticipantDoesNotRequireFriendForRead() {
        ChatAttachmentReference ref = new ChatAttachmentReference();
        ref.setParticipantLow("u1");
        ref.setParticipantHigh("u2");
        assertThat(service.isC2cParticipant("u2", ref)).isTrue();
        assertThat(service.isC2cParticipant("u3", ref)).isFalse();
    }

    @Test
    void emptyGrayAllowlistAllowsAnySender() {
        assertThat(new ChatAttachmentProperties.Gray(java.util.List.of(), java.util.List.of(), true)
            .senderAllowed("anyone")).isTrue();
        assertThat(new ChatAttachmentProperties.Gray(java.util.List.of("u1"), java.util.List.of(), true)
            .senderAllowed("u2")).isFalse();
    }
}
