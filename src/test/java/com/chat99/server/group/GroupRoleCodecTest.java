package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroupRoleCodecTest {

    @Test
    void mapsImRolesToInt() {
        assertThat(GroupRoleCodec.fromImRole("Owner")).isEqualTo(400);
        assertThat(GroupRoleCodec.fromImRole("Admin")).isEqualTo(300);
        assertThat(GroupRoleCodec.fromImRole("Member")).isEqualTo(200);
        assertThat(GroupRoleCodec.fromImRoleOrDefault(null)).isEqualTo(200);
    }

    @Test
    void mapsIntRolesToIm() {
        assertThat(GroupRoleCodec.toImRole(400)).isEqualTo("Owner");
        assertThat(GroupRoleCodec.toImRole(300)).isEqualTo("Admin");
        assertThat(GroupRoleCodec.toImRole(200)).isEqualTo("Member");
    }
}
