package com.chat99.server.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.chat99.server.push.PushDisplayNameResolver;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImChatPushPreviewServiceTest {

    @Mock UserRepository userRepository;
    @Mock PushDisplayNameResolver displayNameResolver;
    @Mock ImAdminClient imAdminClient;
    @Mock ImUserIdService imUserIdService;

    private ImChatPushPreviewService service;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ImChatPushPreviewService(json, userRepository, displayNameResolver, imAdminClient, imUserIdService);
    }

    @Test
    void skipUserTypingStatus() {
        List<?> body = customBody("user_typing_status", null);
        assertThat(service.shouldSkipMessage(body)).isTrue();
    }

    @Test
    void skipRedPacketClaimNotice() {
        List<?> body = customBody("red_packet_claim_notice", null);
        assertThat(service.shouldSkipMessage(body)).isTrue();
    }

    @Test
    void skipRtcCall() {
        List<?> body = customBody("rtc_call", null);
        assertThat(service.shouldSkipMessage(body)).isTrue();
    }

    @Test
    void skipSilentGroupInfoChange() {
        List<?> body = List.of(Map.of(
            "MsgType", "TIMGroupTipElem",
            "MsgContent", Map.of(
                "TipType", 7,
                "OpMember_Account", "op1",
                "GroupChangeInfoList", List.of(Map.of("Type", 1)))));
        assertThat(service.shouldSkipMessage(body)).isTrue();
    }

    @Test
    void skipSetAdminTip() {
        List<?> body = List.of(Map.of(
            "MsgType", "TIMGroupTipElem",
            "MsgContent", Map.of("TipType", 5, "OpMember_Account", "op1")));
        assertThat(service.shouldSkipMessage(body)).isTrue();
    }

    @Test
    void tipInviteSummary() {
        when(displayNameResolver.resolveCallerDisplayName(any(), eq("op1"))).thenReturn("张三");
        when(displayNameResolver.resolveCallerDisplayName(any(), eq("u2"))).thenReturn("李四");
        List<?> body = List.of(Map.of(
            "MsgType", "TIMGroupTipElem",
            "MsgContent", Map.of(
                "TipType", 2,
                "OpMember_Account", "op1",
                "MemberList", List.of(Map.of("Member_Account", "u2")))));
        ImChatPushPreviewService.PreviewContext ctx = groupCtx("viewer", "op1", "测试群");
        ImChatPushPreviewService.ChatPushPreview preview = service.previewGroup(ctx, body);
        assertThat(preview.skip()).isFalse();
        assertThat(preview.body()).isEqualTo("张三邀请李四加入群组");
    }

    @Test
    void tipMemberMuteSummary() {
        when(displayNameResolver.resolveCallerDisplayName(any(), eq("u2"))).thenReturn("王五");
        List<?> body = List.of(Map.of(
            "MsgType", "TIMGroupTipElem",
            "MsgContent", Map.of(
                "TipType", 8,
                "MemberChangeInfoList", List.of(Map.of(
                    "Member_Account", "u2",
                    "ShutUpTime", 60)))));
        ImChatPushPreviewService.PreviewContext ctx = groupCtx("viewer", "op1", "测试群");
        assertThat(service.previewGroup(ctx, body).body()).isEqualTo("王五被禁言");
    }

    @Test
    void customTransferIncoming() {
        List<?> body = customBody("wallet_transfer", """
            {"customType":"wallet_transfer","fromUserId":"from1","toUserId":"to1"}
            """);
        ImChatPushPreviewService.PreviewContext ctx = new ImChatPushPreviewService.PreviewContext(
            "to1", "c2c", "from1", "发送者", null);
        assertThat(service.previewC2c(ctx, body).body()).isEqualTo("[转账] 转账给你");
    }

    @Test
    void customTransferOutgoing() {
        when(displayNameResolver.resolveCallerDisplayName("from1", "to1")).thenReturn("张三");
        List<?> body = customBody("wallet_transfer", """
            {"customType":"wallet_transfer","fromUserId":"from1","toUserId":"to1"}
            """);
        ImChatPushPreviewService.PreviewContext ctx = new ImChatPushPreviewService.PreviewContext(
            "from1", "c2c", "from1", "发送者", null);
        assertThat(service.previewC2c(ctx, body).body()).isEqualTo("[转账] 转账给 张三");
    }

    @Test
    void customRedPacketGreeting() {
        List<?> body = customBody("wallet_red_packet", """
            {"customType":"wallet_red_packet","greeting":"恭喜发财"}
            """);
        ImChatPushPreviewService.PreviewContext ctx = c2cCtx("u1");
        assertThat(service.summarizeMsgBody(ctx, body)).isEqualTo("[红包] 恭喜发财");
    }

    @Test
    void customContactCard() {
        List<?> body = customBody("contact_card", """
            {"customType":"contact_card","contactUserId":"u9","nickname":"李四"}
            """);
        ImChatPushPreviewService.PreviewContext ctx = c2cCtx("u1");
        assertThat(service.summarizeMsgBody(ctx, body)).isEqualTo("[个人名片] 李四");
    }

    @Test
    void customGroupCreate() {
        when(displayNameResolver.resolveCallerDisplayName(any(), eq("op1"))).thenReturn("张三");
        List<?> body = customBody("group_create", """
            {"customType":"group_create","opUserId":"op1","groupName":"产品群"}
            """);
        ImChatPushPreviewService.PreviewContext ctx = groupCtx("viewer", "op1", "产品群");
        assertThat(service.summarizeMsgBody(ctx, body)).isEqualTo("张三创建了群聊「产品群」");
    }

    @Test
    void groupTipBodyWithoutSenderPrefix() {
        when(displayNameResolver.resolveCallerDisplayName(any(), eq("u2"))).thenReturn("李四");
        List<?> body = List.of(Map.of(
            "MsgType", "TIMGroupTipElem",
            "MsgContent", Map.of(
                "TipType", 3,
                "MemberList", List.of(Map.of("Member_Account", "u2")))));
        ImChatPushPreviewService.PreviewContext ctx = groupCtx("viewer", "op1", "测试群");
        assertThat(service.previewGroup(ctx, body).body()).isEqualTo("李四退出了群组");
    }

    @Test
    void relaySummary() {
        List<?> body = List.of(Map.of(
            "MsgType", "TIMRelayElem",
            "MsgContent", Map.of("Title", "群聊的聊天记录")));
        ImChatPushPreviewService.PreviewContext ctx = c2cCtx("u1");
        assertThat(service.summarizeMsgBody(ctx, body)).isEqualTo("[合并转发] 群聊的聊天记录");
    }

    @Test
    void customGroupTipUsesPreviewAbstractNotDesc() {
        String data = """
            {"businessID":"group_tip","version":1,"action":"member_set_admin",\
            "opUserId":"user_a","opUserName":"张三","memberUserIds":["user_b"],\
            "memberNames":["李四"],"previewAbstract":"张三将李四设置为管理员"}
            """;
        List<?> body = List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Data", data.trim(), "Desc", "group_tip")));
        ImChatPushPreviewService.PreviewContext ctx = groupCtx("viewer", "user_a", "产品群");
        ImChatPushPreviewService.ChatPushPreview preview = service.previewGroup(ctx, body);
        assertThat(preview.skip()).isFalse();
        assertThat(preview.title()).isEqualTo("产品群");
        assertThat(preview.body()).isEqualTo("张三将李四设置为管理员");
        assertThat(preview.body()).isNotEqualTo("group_tip");
    }

    @Test
    void customGroupTipComposesWhenPreviewMissing() {
        String data = """
            {"businessID":"group_tip","action":"member_removed",\
            "opUserName":"张三","memberNames":["李四"]}
            """;
        List<?> body = List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Data", data.trim(), "Desc", "group_tip")));
        ImChatPushPreviewService.PreviewContext ctx = groupCtx("viewer", "op1", "测试群");
        assertThat(service.previewGroup(ctx, body).body()).isEqualTo("张三将李四踢出群组");
    }

    @Test
    void customGroupTipBodyWithoutSenderPrefix() {
        String data = """
            {"businessID":"group_tip","action":"member_left","memberNames":["李四"]}
            """;
        List<?> body = List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Data", data.trim())));
        ImChatPushPreviewService.PreviewContext ctx = groupCtx("viewer", "op1", "测试群");
        assertThat(service.previewGroup(ctx, body).body()).isEqualTo("李四退出群聊");
    }

    @Test
    void customUnknownBusinessIdDoesNotEchoTypeName() {
        List<?> body = customBody("foo_bar", "{\"businessID\":\"foo_bar\"}");
        ImChatPushPreviewService.PreviewContext ctx = c2cCtx("u1");
        assertThat(service.summarizeMsgBody(ctx, body)).isEqualTo("[消息]");
    }

    @Test
    void chatAttachmentPreviewUsesKindLabel() {
        String data = """
            {"type":"chat.attachment","version":1,"attachmentId":"att_1",\
            "referenceId":"ref_1","kind":"video","name":"旅行.mp4"}
            """;
        List<?> body = customBody("chat.attachment", data);
        ImChatPushPreviewService.PreviewContext ctx = c2cCtx("u1");
        assertThat(service.summarizeMsgBody(ctx, body)).contains("[视频]");
    }

    private static ImChatPushPreviewService.PreviewContext c2cCtx(String recipient) {
        return new ImChatPushPreviewService.PreviewContext(recipient, "c2c", "from1", "发送者", null);
    }

    private static ImChatPushPreviewService.PreviewContext groupCtx(String recipient,
                                                                    String from,
                                                                    String groupName) {
        return new ImChatPushPreviewService.PreviewContext(recipient, "group", from, "发送者", groupName);
    }

    private static List<?> customBody(String businessId, String dataJson) {
        String data = dataJson != null ? dataJson.trim()
            : "{\"businessID\":\"" + businessId + "\"}";
        return List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Data", data)));
    }
}
