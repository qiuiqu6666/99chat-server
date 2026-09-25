package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupMuteAllSendGuardServiceTest {

    @Mock GroupProjectionService projection;
    @Mock GroupMemberRepository memberRepository;
    @Mock ImUserIdService imUserIdService;

    private GroupMuteAllSendGuardService guard;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        guard = new GroupMuteAllSendGuardService(projection, memberRepository, imUserIdService, json);
    }

    @Test
    void allowsWhenNotMuted() {
        when(projection.isShutUpAll("g1")).thenReturn(false);
        assertThat(guard.evaluate(baseBody("u1", textElem()))).isEmpty();
    }

    @Test
    void rejectsOrdinaryMemberTextWhenMuted() {
        when(projection.isShutUpAll("g1")).thenReturn(true);
        when(imUserIdService.isSpecialImAccount("u1")).thenReturn(false);
        when(memberRepository.findById(new GroupMemberId("g1", "u1")))
            .thenReturn(Optional.of(member("u1", GroupRoleCodec.MEMBER)));

        assertThat(guard.evaluate(baseBody("u1", textElem())))
            .contains(GroupMuteAllSendGuardService.REJECT_CODE);
    }

    @Test
    void channelRejectsSubscriberEvenWhenMuteProjectionWasReset() {
        when(projection.isShutUpAll("g1")).thenReturn(false);
        when(projection.isChannel("g1")).thenReturn(true);
        when(imUserIdService.isSpecialImAccount("u1")).thenReturn(false);
        when(memberRepository.findById(new GroupMemberId("g1", "u1")))
            .thenReturn(Optional.of(member("u1", GroupRoleCodec.MEMBER)));

        assertThat(guard.evaluate(baseBody("u1", textElem())))
            .contains(GroupMuteAllSendGuardService.REJECT_CODE);
    }

    @Test
    void channelRejectsSubscriberCustomTip() throws Exception {
        when(projection.isChannel("g1")).thenReturn(true);
        when(memberRepository.findById(new GroupMemberId("g1", "u1")))
            .thenReturn(Optional.of(member("u1", GroupRoleCodec.MEMBER)));

        assertThat(guard.evaluate(baseBody("u1", tipElem())))
            .contains(GroupMuteAllSendGuardService.REJECT_CODE);
    }

    @Test
    void channelAllowsOwnerAndAdmin() {
        when(projection.isChannel("g1")).thenReturn(true);
        when(memberRepository.findById(new GroupMemberId("g1", "admin1")))
            .thenReturn(Optional.of(member("admin1", GroupRoleCodec.ADMIN)));
        when(memberRepository.findById(new GroupMemberId("g1", "owner1")))
            .thenReturn(Optional.of(member("owner1", GroupRoleCodec.OWNER)));

        assertThat(guard.evaluate(baseBody("admin1", textElem()))).isEmpty();
        assertThat(guard.evaluate(baseBody("owner1", textElem()))).isEmpty();
    }

    @Test
    void allowsOrdinaryMemberPureTipWhenMuted() throws Exception {
        when(projection.isShutUpAll("g1")).thenReturn(true);
        when(imUserIdService.isSpecialImAccount("u1")).thenReturn(false);
        when(memberRepository.findById(new GroupMemberId("g1", "u1")))
            .thenReturn(Optional.of(member("u1", GroupRoleCodec.MEMBER)));

        assertThat(guard.evaluate(baseBody("u1", tipElem()))).isEmpty();
    }

    @Test
    void rejectsMixedTipAndTextWhenMuted() throws Exception {
        when(projection.isShutUpAll("g1")).thenReturn(true);
        when(imUserIdService.isSpecialImAccount("u1")).thenReturn(false);
        when(memberRepository.findById(new GroupMemberId("g1", "u1")))
            .thenReturn(Optional.of(member("u1", GroupRoleCodec.MEMBER)));

        Map<String, Object> body = Map.of(
            "GroupId", "g1",
            "From_Account", "u1",
            "MsgBody", List.of(tipElem(), textElem()));
        assertThat(guard.evaluate(body)).contains(GroupMuteAllSendGuardService.REJECT_CODE);
    }

    @Test
    void allowsAdminTextWhenMuted() {
        when(projection.isShutUpAll("g1")).thenReturn(true);
        when(imUserIdService.isSpecialImAccount("admin1")).thenReturn(false);
        when(memberRepository.findById(new GroupMemberId("g1", "admin1")))
            .thenReturn(Optional.of(member("admin1", GroupRoleCodec.ADMIN)));

        assertThat(guard.evaluate(baseBody("admin1", textElem()))).isEmpty();
    }

    @Test
    void allowsSpecialAccount() {
        when(projection.isShutUpAll("g1")).thenReturn(true);
        when(imUserIdService.isSpecialImAccount("administrator")).thenReturn(true);

        assertThat(guard.evaluate(baseBody("administrator", textElem()))).isEmpty();
    }

    @Test
    void rejectsWhenRoleMissing() {
        when(projection.isShutUpAll("g1")).thenReturn(true);
        when(imUserIdService.isSpecialImAccount("ghost")).thenReturn(false);
        when(memberRepository.findById(new GroupMemberId("g1", "ghost"))).thenReturn(Optional.empty());
        when(imUserIdService.findBusinessUserId("ghost")).thenReturn(Optional.of("ghost"));

        assertThat(guard.evaluate(baseBody("ghost", textElem())))
            .contains(GroupMuteAllSendGuardService.REJECT_CODE);
    }

    private static Map<String, Object> baseBody(String from, Map<String, Object> elem) {
        return Map.of(
            "GroupId", "g1",
            "From_Account", from,
            "MsgBody", List.of(elem));
    }

    private static Map<String, Object> textElem() {
        return Map.of(
            "MsgType", "TIMTextElem",
            "MsgContent", Map.of("Text", "hi"));
    }

    private Map<String, Object> tipElem() throws Exception {
        String data = json.writeValueAsString(Map.of(
            "businessID", "group_tip",
            "action", "member_added"));
        return Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Data", data));
    }

    private static GroupMember member(String userId, int role) {
        GroupMember m = new GroupMember();
        m.setGroupId("g1");
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }
}
