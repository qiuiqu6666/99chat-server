package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupFanoutTargetResolverTest {

    @Mock GroupProjectionService projection;
    @Mock ImAdminClient im;

    GroupFanoutTargetResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new GroupFanoutTargetResolver(projection, im, GroupFanoutProperties.defaults());
    }

    @Test
    void resolve_usesLocalAndSkipsImWhenNonEmpty() {
        when(projection.listLocalMemberUserIds("g1")).thenReturn(List.of("a", "b"));

        assertThat(resolver.resolveMemberTargets("g1")).containsExactly("a", "b");
        verify(im, never()).listGroupMemberUserIds(anyString(), anyInt());
    }

    @Test
    void resolve_fallsBackToImWhenLocalEmpty() {
        when(projection.listLocalMemberUserIds("g1")).thenReturn(List.of());
        when(im.listGroupMemberUserIds(eq("g1"), anyInt())).thenReturn(List.of("x", "y"));

        assertThat(resolver.resolveMemberTargets("g1")).containsExactly("x", "y");
        verify(im).listGroupMemberUserIds(eq("g1"), anyInt());
    }

    @Test
    void resolveUnion_mergesExtras() {
        when(projection.listLocalMemberUserIds("g1")).thenReturn(List.of("a"));

        assertThat(resolver.resolveMemberTargetsUnion("g1", List.of("left-user")))
            .containsExactly("a", "left-user");
    }
}
