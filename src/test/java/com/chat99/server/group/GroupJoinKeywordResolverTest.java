package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroupJoinKeywordResolverTest {

    @Test
    void migratedCustomMId_returnsOnlyItself() {
        assertThat(GroupJoinKeywordResolver.candidateGroupIds("m2ERSS3N5CX"))
            .containsExactly("m2ERSS3N5CX");
    }

    @Test
    void migratedCommunityId_returnsOnlyItself() {
        assertThat(GroupJoinKeywordResolver.candidateGroupIds("@TGS#_mcKE2PNMM62C2"))
            .containsExactly("@TGS#_mcKE2PNMM62C2");
    }

    @Test
    void corruptedCommunityWrapOfM_prefersM() {
        assertThat(GroupJoinKeywordResolver.candidateGroupIds("@TGS#_@TGS#m2ERSS3N5CX"))
            .containsExactly("m2ERSS3N5CX", "@TGS#_@TGS#m2ERSS3N5CX");
    }

    @Test
    void oldPublicTgs_prefersMigratedM() {
        assertThat(GroupJoinKeywordResolver.candidateGroupIds("@TGS#2ERSS3N5CX"))
            .containsExactly("m2ERSS3N5CX", "@TGS#2ERSS3N5CX", "@TGS#_@TGS#2ERSS3N5CX");
    }

    @Test
    void shortAlias_resolvesCommunityAndMigratedM() {
        assertThat(GroupJoinKeywordResolver.candidateGroupIds("@cQN5PNMM62CN"))
            .containsExactly(
                "mcQN5PNMM62CN",
                "@TGS#cQN5PNMM62CN",
                "@TGS#_@TGS#cQN5PNMM62CN");
    }

    @Test
    void bareSuffix_resolvesMigratedMThenLegacy() {
        assertThat(GroupJoinKeywordResolver.candidateGroupIds("cQN5PNMM62CN"))
            .containsExactly(
                "mcQN5PNMM62CN",
                "@TGS#cQN5PNMM62CN",
                "@TGS#_@TGS#cQN5PNMM62CN");
    }

    @Test
    void fullCommunityId_returnsSingleCandidate() {
        assertThat(GroupJoinKeywordResolver.candidateGroupIds("@TGS#_@TGS#cQN5PNMM62CN"))
            .containsExactly("@TGS#_@TGS#cQN5PNMM62CN");
    }

    @Test
    void publicGroupId_includesMigratedMBeforeCommunityVariant() {
        assertThat(GroupJoinKeywordResolver.candidateGroupIds("@TGS#222HLNM5CL"))
            .containsExactly(
                "m222HLNM5CL",
                "@TGS#222HLNM5CL",
                "@TGS#_@TGS#222HLNM5CL");
    }
}
