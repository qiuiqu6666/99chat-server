package com.chat99.server.im;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.adminapi.AdminDashboardCounterService;
import com.chat99.server.common.AppSettingService;
import com.chat99.server.group.GroupProjectionService;
import com.chat99.server.group.UserOwnedGroupService;
import com.chat99.server.push.PushConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImDashboardGroupMessageDedupTest {

    @Mock PushConfigService pushConfig;
    @Mock AppSettingService settings;
    @Mock ImCallbackVerifier callbackVerifier;
    @Mock AdminDashboardCounterService dashboardCounter;
    @Mock ImPushDedupStore dedupStore;
    @Mock GroupMessageBucketDedup groupMessageBucketDedup;
    @Mock UserOwnedGroupService ownedGroupService;
    @Mock GroupProjectionService groupProjection;
    @Mock ImUserIdService imUserIdService;

    private ImDashboardStatsCallbackService service;

    @BeforeEach
    void setUp() {
        service = new ImDashboardStatsCallbackService(
            pushConfig, settings, callbackVerifier, dashboardCounter, dedupStore,
            groupMessageBucketDedup, ownedGroupService, groupProjection, imUserIdService,
            new ObjectMapper());
        when(pushConfig.getAllowedSdkAppIds()).thenReturn("");
        when(settings.getInt(AppSettingService.IM_SDK_APP_ID, 0)).thenReturn(0);
    }

    @Test
    void bucketMsgIdIncrementsOnlyOnFirstSadd() {
        when(groupMessageBucketDedup.ttlHours()).thenReturn(48);
        when(groupMessageBucketDedup.markIfNew(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(true);
        service.recordStats("1", "Group.CallbackAfterSendMsg", null, "sign", "1", body(
            "144115249996004533-" + (System.currentTimeMillis() / 1000) + "-68427540", null, null));
        verify(dashboardCounter).incrementGroupMessage();
        verify(dedupStore, never()).markIfNew(anyString());
    }

    @Test
    void duplicateBucketMsgIdDoesNotIncrement() {
        when(groupMessageBucketDedup.ttlHours()).thenReturn(48);
        when(groupMessageBucketDedup.markIfNew(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(false);
        service.recordStats("1", "Group.CallbackAfterSendMsg", null, "sign", "1", body(
            "144115249996004533-" + (System.currentTimeMillis() / 1000) + "-68427540", null, null));
        verify(dashboardCounter, never()).incrementGroupMessage();
    }

    @Test
    void unparseableMsgIdUsesLegacyKeyAndIgnoresMsgSeq() {
        when(groupMessageBucketDedup.ttlHours()).thenReturn(48);
        when(dedupStore.markIfNew("dash|group|@TGS#g|not-a-msg")).thenReturn(true);
        service.recordStats("1", "Group.CallbackAfterSendMsg", null, "sign", "1",
            body("not-a-msg", "99", "fallback"));
        verify(dedupStore).markIfNew("dash|group|@TGS#g|not-a-msg");
        verify(dashboardCounter).incrementGroupMessage();
    }

    @Test
    void msgSeqFallbackUsesLegacyDedup() {
        when(dedupStore.markIfNew("dash|group|@TGS#g|99")).thenReturn(true);
        service.recordStats("1", "Group.CallbackAfterSendMsg", null, "sign", "1",
            body(null, "99", "fallback"));
        verify(dedupStore).markIfNew("dash|group|@TGS#g|99");
        verify(groupMessageBucketDedup, never()).markIfNew(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void blankIdentifiersDoNotWriteRedisOrIncrement() {
        service.recordStats("1", "Group.CallbackAfterSendMsg", null, "sign", "1",
            body("  ", "", null));
        verify(dedupStore, never()).markIfNew(anyString());
        verify(dashboardCounter, never()).incrementGroupMessage();
    }

    private static String body(String msgId, String msgSeq, String msgKey) {
        StringBuilder json = new StringBuilder();
        json.append("{\"SendMsgResult\":0,\"GroupId\":\"@TGS#g\"");
        if (msgId != null) {
            json.append(",\"MsgId\":\"").append(msgId).append('"');
        }
        if (msgSeq != null) {
            json.append(",\"MsgSeq\":\"").append(msgSeq).append('"');
        }
        if (msgKey != null) {
            json.append(",\"MsgKey\":\"").append(msgKey).append('"');
        }
        json.append('}');
        return json.toString();
    }
}
