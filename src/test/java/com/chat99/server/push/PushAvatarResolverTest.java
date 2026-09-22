package com.chat99.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.chat99.server.auth.AuthProperties;
import com.chat99.server.group.GroupAvatarDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PushAvatarResolverTest {

    @Mock
    private com.chat99.server.im.ImAdminClient imAdmin;

    @Mock
    private com.chat99.server.im.ImUserIdService imUserIdService;

    @Mock
    private com.chat99.server.user.UserRepository userRepository;

    @Mock
    private GroupAvatarDefaults groupAvatarDefaults;

    @Test
    void resolveUserAvatarUrlUsesPublicDefaultForBlankUserId() {
        PushAvatarResolver resolver = resolver();
        assertThat(resolver.resolveUserAvatarUrl(null))
            .isEqualTo("https://cdn.example.com/default-user.png");
        assertThat(resolver.resolveUserAvatarUrl("  "))
            .isEqualTo("https://cdn.example.com/default-user.png");
    }

    @Test
    void resolveGroupAvatarUrlReturnsNullForBlankGroupId() {
        PushAvatarResolver resolver = resolver();
        assertThat(resolver.resolveGroupAvatarUrl(null)).isNull();
        assertThat(resolver.resolveGroupAvatarUrl("")).isNull();
    }

    private PushAvatarResolver resolver() {
        return new PushAvatarResolver(
            imAdmin,
            imUserIdService,
            userRepository,
            groupAvatarDefaults,
            new AuthProperties("https://cdn.example.com/default-user.png"));
    }
}
