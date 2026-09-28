package com.chat99.server.im;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    @Mock GroupPushStream groupPushStream;

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
            new ObjectMapper(), pushPreviewService, pushAvatarSupport, imUserIdService,
            groupPushStream);

        when(pushConfig.isImCallbackEnabled()).thenReturn(true);
        when(pushConfig.isChatPushEnabled()).thenReturn(true);
        when(pushConfig.getAllowedSdkAppIds()).thenReturn(null);
        when(pushService.enabled()).thenReturn(true);
        when(settings.getInt(eq(AppSettingService.IM_SDK_APP_ID), eq(0))).thenReturn(123);
        when(pushPreviewService.shouldSkipMessage(any())).thenReturn(false);
        when(dedupStore.markIfNew(anyString())).thenReturn(true);
        when(imUserIdService.toBusinessForDisplay("fromBiz")).thenReturn("fromBiz");
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

        verify(groupPushStream).enqueue(eq(false), any());
        verify(imAdmin, never()).getGroupBaseInfo(anyString());
        verify(groupMemberCacheService, never()).memberUserIds(anyString());
        verify(groupPushAggregationService, never()).enqueue(any(), anyList());
    }

    @Test
    void duplicateGroupAfterSendDoesNotEnqueue() {
        when(dedupStore.markIfNew(anyString())).thenReturn(false);
        String body = """
            {
              "CallbackCommand": "Group.CallbackAfterSendMsg",
              "GroupId": "g1",
              "From_Account": "fromBiz",
              "MsgId": "m1",
              "OnlineOnlyFlag": 0,
              "SendMsgResult": 0,
              "MsgBody": [{"MsgType":"TIMTextElem","MsgContent":{"Text":"hi"}}]
            }
            """;
        service.processAfterSend("123", "Group.CallbackAfterSendMsg", body);
        verify(groupPushStream, never()).enqueue(anyBoolean(), any());
    }

    @Test
    void mentionEnqueuesPriorityStream() {
        String body = """
            {
              "CallbackCommand": "Group.CallbackAfterSendMsg",
              "GroupId": "g1",
              "From_Account": "fromBiz",
              "MsgId": "m2",
              "OnlineOnlyFlag": 0,
              "SendMsgResult": 0,
              "MsgBody": [{"MsgType":"TIMTextElem","MsgContent":{"Text":"hi"}}],
              "GroupAtInfo": [{"GroupAt_Account":"user123"}]
            }
            """;
        when(imUserIdService.toBusinessForDisplay("user123")).thenReturn("user123");
        service.processAfterSend("123", "Group.CallbackAfterSendMsg", body);
        verify(groupPushStream).enqueue(eq(true), any());
    }

    @Test
    void fullStreamThrows() {
        org.mockito.Mockito.doThrow(new GroupPushStreamFullException("push:group:after-send"))
            .when(groupPushStream).enqueue(eq(false), any());
        String body = """
            {
              "CallbackCommand": "Group.CallbackAfterSendMsg",
              "GroupId": "g1",
              "From_Account": "fromBiz",
              "MsgId": "m3",
              "OnlineOnlyFlag": 0,
              "SendMsgResult": 0,
              "MsgBody": [{"MsgType":"TIMTextElem","MsgContent":{"Text":"hi"}}]
            }
            """;
        org.junit.jupiter.api.Assertions.assertThrows(GroupPushStreamFullException.class,
            () -> service.processAfterSend("123", "Group.CallbackAfterSendMsg", body));
    }
}
