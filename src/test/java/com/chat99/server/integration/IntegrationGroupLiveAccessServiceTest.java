package com.chat99.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.group.GroupAccessService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class IntegrationGroupLiveAccessServiceTest {

    @Mock GroupAccessService groupAccess;

    IntegrationGroupLiveAccessService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationGroupLiveAccessService(groupAccess);
    }

    @Test
    void ownerRolePasses() {
        doNothing().when(groupAccess).requireOwnerRole("m123", "owner01");
        Map<String, Object> out = service.check("m123", "owner01", "OWNER");
        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("role")).isEqualTo("Owner");
        verify(groupAccess).requireOwnerRole("m123", "owner01");
    }

    @Test
    void memberRolePasses() {
        when(groupAccess.requireMemberRole("m123", "user01")).thenReturn("Member");
        Map<String, Object> out = service.check("m123", "user01", "MEMBER");
        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("role")).isEqualTo("Member");
    }

    @Test
    void adminRolePassesForOwner() {
        when(groupAccess.requireAdminRole("m123", "owner01")).thenReturn("Owner");
        Map<String, Object> out = service.check("m123", "owner01", "ADMIN");
        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("role")).isEqualTo("Owner");
    }

    @Test
    void ownerRequiredRejectsMember() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER"))
            .when(groupAccess).requireOwnerRole("m123", "user01");
        assertThatThrownBy(() -> service.check("m123", "user01", "OWNER"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("NOT_GROUP_OWNER");
    }

    @Test
    void blankInputRejected() {
        assertThatThrownBy(() -> service.check(" ", "user01", "OWNER"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("INVALID_INPUT");
    }

    @Test
    void unknownRoleRejected() {
        assertThatThrownBy(() -> service.check("m123", "user01", "GUEST"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("INVALID_INPUT");
    }
}
