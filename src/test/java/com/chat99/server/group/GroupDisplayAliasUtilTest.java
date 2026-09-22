package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroupDisplayAliasUtilTest {

    @Test
    void communityAlias() {
        assertThat(GroupDisplayAliasUtil.compute("Community", "@TGS#2ABCDEF")).isEqualTo("@2ABCDEF");
        assertThat(GroupDisplayAliasUtil.compute("Community", "@TGS#_abc")).isEqualTo("@abc");
    }

    @Test
    void nonCommunityEmpty() {
        assertThat(GroupDisplayAliasUtil.compute("Public", "@TGS#2ABCDEF")).isEmpty();
    }
}
