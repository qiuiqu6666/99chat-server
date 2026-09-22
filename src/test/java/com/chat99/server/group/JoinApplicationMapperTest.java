package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JoinApplicationMapperTest {

    private static final String GROUP = "@TGS#_abc";
    private static final String INVITER = "inviter1";
    private static final String INVITEE = "invitee1";
    private static final String ADMIN = "admin001";

    @Test
    void inviterPendingInvite_cannotHandle() {
        GroupJoinApplication app = invitePending(INVITER, INVITEE);
        assertThat(JoinApplicationMapper.resolveViewerRole(INVITER, app, GroupRoleCodec.MEMBER))
            .isEqualTo("inviter");
        assertThat(JoinApplicationMapper.canHandle(INVITER, app, GroupRoleCodec.MEMBER)).isFalse();
    }

    @Test
    void adminPendingInviteFromOther_canHandle() {
        GroupJoinApplication app = invitePending(INVITER, INVITEE);
        assertThat(JoinApplicationMapper.resolveViewerRole(ADMIN, app, GroupRoleCodec.ADMIN))
            .isEqualTo("admin");
        assertThat(JoinApplicationMapper.canHandle(ADMIN, app, GroupRoleCodec.ADMIN)).isTrue();
    }

    @Test
    void adminOwnPendingInvite_cannotHandle() {
        GroupJoinApplication app = invitePending(ADMIN, INVITEE);
        assertThat(JoinApplicationMapper.resolveViewerRole(ADMIN, app, GroupRoleCodec.ADMIN))
            .isEqualTo("inviter");
        assertThat(JoinApplicationMapper.canHandle(ADMIN, app, GroupRoleCodec.ADMIN)).isFalse();
    }

    @Test
    void handledInvite_cannotHandle() {
        GroupJoinApplication app = invitePending(INVITER, INVITEE);
        app.setStatus(GroupJoinApplicationStatus.approved);
        assertThat(JoinApplicationMapper.canHandle(ADMIN, app, GroupRoleCodec.ADMIN)).isFalse();
    }

    @Test
    void handledApplication_includesHandlerIdentityAndNickname() {
        GroupJoinApplication app = invitePending(INVITER, INVITEE);
        app.setStatus(GroupJoinApplicationStatus.approved);
        app.setHandledBy(ADMIN);

        var item = JoinApplicationMapper.toItem(
            app,
            Map.of(),
            Map.of(ADMIN, new GroupMemberEnrichmentService.UserBrief("张三", null)),
            null,
            null);

        assertThat(item.handlerUserId()).isEqualTo(ADMIN);
        assertThat(item.handledByUserId()).isEqualTo(ADMIN);
        assertThat(item.handledByNickName()).isEqualTo("张三");
    }

    private static GroupJoinApplication invitePending(String fromUserId, String toUserId) {
        GroupJoinApplication app = new GroupJoinApplication();
        app.setId(1L);
        app.setGroupId(GROUP);
        app.setType(GroupJoinApplicationType.invite);
        app.setFromUserId(fromUserId);
        app.setToUserId(toUserId);
        app.setStatus(GroupJoinApplicationStatus.pending);
        return app;
    }
}
