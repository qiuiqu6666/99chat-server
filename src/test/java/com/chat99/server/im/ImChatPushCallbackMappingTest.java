package com.chat99.server.im;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.call.CallWebhookService;
import com.chat99.server.common.AppSettingService;
import com.chat99.server.notify.PlatformWalletNoticeProperties;
import com.chat99.server.notify.SystemNotifyProperties;
import com.chat99.server.push.ConversationNotifyService;
import com.chat99.server.push.PushAvatarResolver;
import com.chat99.server.push.PushConfigService;
import com.chat99.server.push.PushFocusService;
import com.chat99.server.push.PushService;
import com.chat99.server.push.VoipCallPushTrigger;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImChatPushCallbackMappingTest {

    @Mock PushConfigService pushConfig;
    @Mock AppSettingService settings;
    @Mock ImAdminClient imAdmin;
    @Mock PushService pushService;
    @Mock UserRepository userRepository;
    @Mock ImPushDedupStore dedupStore;
    @Mock ImCallbackVerifier callbackVerifier;
    @Mock VoipCallPushTrigger voipCallPushTrigger;
    @Mock PushAvatarResolver pushAvatarResolver;
    @Mock ConversationNotifyService conversationNotifyService;
    @Mock GroupMemberCacheService groupMemberCacheService;
    @Mock GroupPushAggregationService groupPushAggregationService;
    @Mock PushFocusService pushFocusService;
    @Mock CallWebhookService callWebhookService;
    @Mock ImChatPushPreviewService pushPreviewService;
    @Mock ImChatPushAvatarSupport pushAvatarSupport;
    @Mock ImUserIdService imUserIdService;

    ImChatPushCallbackService service;

    @BeforeEach
    void setUp() {
        SystemNotifyProperties systemNotifyProperties =
            new SystemNotifyProperties("99Messenger", null, null, false, false, null, false, null);
        PlatformWalletNoticeProperties walletNoticeProperties =
            new PlatformWalletNoticeProperties("99Chat", null, null, null, false, false, null, true);

        service = new ImChatPushCallbackService(
            pushConfig, settings, imAdmin, pushService, userRepository, dedupStore,
            systemNotifyProperties, walletNoticeProperties, callbackVerifier, voipCallPushTrigger,
            pushAvatarResolver, conversationNotifyService, groupMemberCacheService,
            groupPushAggregationService, pushFocusService, callWebhookService,
            new ObjectMapper(), pushPreviewService, pushAvatarSupport, imUserIdService);

        when(pushConfig.isImCallbackEnabled()).thenReturn(true);
        when(pushConfig.isChatPushEnabled()).thenReturn(true);
        when(pushConfig.getAllowedSdkAppIds()).thenReturn(null);
        when(pushService.enabled()).thenReturn(true);
        when(settings.getInt(eq(AppSettingService.IM_SDK_APP_ID), eq(0))).thenReturn(123);
        when(pushPreviewService.shouldSkipMessage(any())).thenReturn(false);
        when(imAdmin.getGroupBaseInfo(anyString())).thenReturn(Optional.empty());
        when(pushAvatarSupport.resolveAvatarUrl(any(), any(), any())).thenReturn(null);
        when(userRepository.findByUserId(anyString())).thenReturn(Optional.empty());
        when(imUserIdService.toBusinessForDisplay("fromBiz")).thenReturn("fromBiz");
        when(imUserIdService.toBusinessForDisplayBatch(anyList()))
            .thenReturn(Map.of("q14gkm5swv", "q14gkm5swv", "fromBiz", "fromBiz"));
        when(groupMemberCacheService.memberUserIds("g1")).thenReturn(List.of("q14gkm5swv", "fromBiz"));
        when(groupPushAggregationService.enqueue(any(), anyList())).thenReturn(1);
    }

    @Test
    void processAfterSend_group_enqueuesMemberIdsWithoutMapping() {
        String body = """
            {
              "CallbackCommand": "Group.CallbackAfterSendMsg",
              "GroupId": "g1",
              "From_Account": "fromBiz",
              "MsgId": "m1",
              "OnlineOnlyFlag": 0,
              "SendMsgResult": 0,
              "MsgBody": [{"MsgType":"TIMTextElem","MsgContent":{"Text":"hi"}}],
              "GroupAtInfo": []
            }
            """;

        service.processAfterSend("123", "Group.CallbackAfterSendMsg", body);

        ArgumentCaptor<GroupPushAggregationService.GroupMessageEvent> eventCaptor =
            ArgumentCaptor.forClass(GroupPushAggregationService.GroupMessageEvent.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> membersCaptor = ArgumentCaptor.forClass(List.class);
        verify(groupPushAggregationService).enqueue(eventCaptor.capture(), membersCaptor.capture());

        GroupPushAggregationService.GroupMessageEvent event = eventCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(event.fromAccount()).isEqualTo("fromBiz");
        org.assertj.core.api.Assertions.assertThat(membersCaptor.getValue())
            .containsExactly("q14gkm5swv", "fromBiz");
        org.assertj.core.api.Assertions.assertThat(event.mentionedUserIds()).isEqualTo(Set.of());
    }
}
