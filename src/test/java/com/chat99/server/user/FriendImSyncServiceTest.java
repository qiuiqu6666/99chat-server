package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FriendImSyncServiceTest {

    @Mock
    ImAdminClient im;

    FriendImSyncService service;

    @BeforeEach
    void setUp() {
        service = new FriendImSyncService(im);
    }

    @Test
    void trySyncAddBoth_success() {
        service.trySyncAddBoth("a", "b");

        verify(im).addFriendBoth("a", "b", "AddSource_Type_Server", null);
    }

    @Test
    void trySyncAddBoth_failure_throws() {
        doThrow(new ImRestException("FRIEND_ADD_FAILED", 30002))
            .when(im).addFriendBoth(eq("a"), eq("b"), any(), any());

        assertThatThrownBy(() -> service.trySyncAddBoth("a", "b"))
            .isInstanceOf(ImRestException.class)
            .hasMessage("FRIEND_ADD_FAILED");
    }

    @Test
    void trySyncDeleteBoth_failure_throws() {
        doThrow(new ImRestException("FRIEND_DELETE_FAILED", 1))
            .when(im).deleteFriendBoth("a", "b");

        assertThatThrownBy(() -> service.trySyncDeleteBoth("a", "b"))
            .isInstanceOf(ImRestException.class)
            .hasMessage("FRIEND_DELETE_FAILED");
    }

    @Test
    void trySyncRemark_clear_passesEmptyString() {
        service.trySyncRemark("a", "b", null);

        verify(im).updateFriendRemark("a", "b", "");
    }

    @Test
    void trySyncRemark_failure_throws() {
        doThrow(new ImRestException("FRIEND_UPDATE_FAILED", 2))
            .when(im).updateFriendRemark("a", "b", "hello");

        assertThatThrownBy(() -> service.trySyncRemark("a", "b", "hello"))
            .isInstanceOf(ImRestException.class)
            .hasMessage("FRIEND_UPDATE_FAILED");
    }
}
