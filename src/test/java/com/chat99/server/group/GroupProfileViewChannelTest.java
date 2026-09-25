package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroupProfileViewChannelTest {

    @Test
    void communityChannelHasDistinctBusinessMarkerAndPreservesItInCopies() {
        GroupProfile profile = new GroupProfile();
        profile.setGroupId("@TGS#channel");
        profile.setGroupType("Community");
        profile.setGroupName("Announcements");
        profile.setChannel(true);
        GroupMember member = new GroupMember();
        member.setGroupId(profile.getGroupId());
        member.setUserId("owner");

        GroupProfileView view = GroupProfileView.forDetail(profile, member, false);
        assertThat(view.groupType()).isEqualTo("Community");
        assertThat(view.channel()).isTrue();
        assertThat(view.withAvatarUrl("avatar").withMemberCount(5).channel()).isTrue();

        profile.setChannel(false);
        assertThat(GroupProfileView.forDetail(profile, member, false).channel()).isFalse();
    }
}
