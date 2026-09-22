package com.chat99.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.chat99.server.adminapi.AdminAccount;
import com.chat99.server.adminapi.AdminAccountRepository;
import com.chat99.server.adminapi.AdminJwtService;
import com.chat99.server.adminapi.AdminSessionService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class IntegrationAdminGroupLiveCheckServiceTest {

    @Mock AdminJwtService adminJwtService;
    @Mock AdminSessionService sessionService;
    @Mock AdminAccountRepository accountRepository;

    IntegrationAdminGroupLiveCheckService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationAdminGroupLiveCheckService(adminJwtService, sessionService, accountRepository);
    }

    @Test
    void superAdminGetsReadAndWrite() {
        when(adminJwtService.parse("tok")).thenReturn(
            new AdminJwtService.AdminTokenClaims("root", "j1", List.of("admin.manage")));
        when(sessionService.isActive("root", "j1")).thenReturn(true);
        AdminAccount account = new AdminAccount();
        account.setEnabled(true);
        account.setPermissionsCsv("admin.manage,dashboard.view");
        when(accountRepository.findByUsername("root")).thenReturn(Optional.of(account));

        Map<String, Object> out = service.check("Bearer tok");

        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("permissions")).isEqualTo(List.of("group_live.read", "group_live.write"));
    }

    @Test
    void rejectsMissingBearer() {
        assertThatThrownBy(() -> service.check(null))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("UNAUTHORIZED");
    }

    @Test
    void readOnlyKeepsReadWithoutWrite() {
        when(adminJwtService.parse("tok")).thenReturn(
            new AdminJwtService.AdminTokenClaims("ops", "j3", List.of("group_live.read")));
        when(sessionService.isActive("ops", "j3")).thenReturn(true);
        AdminAccount account = new AdminAccount();
        account.setEnabled(true);
        account.setPermissionsCsv("group_live.read");
        when(accountRepository.findByUsername("ops")).thenReturn(Optional.of(account));

        Map<String, Object> out = service.check("Bearer tok");

        assertThat(out.get("permissions")).isEqualTo(List.of("group_live.read"));
    }

    @Test
    void rejectsNoGroupLivePermission() {
        when(adminJwtService.parse("tok")).thenReturn(
            new AdminJwtService.AdminTokenClaims("ops", "j2", List.of("user.read")));
        when(sessionService.isActive("ops", "j2")).thenReturn(true);
        AdminAccount account = new AdminAccount();
        account.setEnabled(true);
        account.setPermissionsCsv("user.read");
        when(accountRepository.findByUsername("ops")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.check("Bearer tok"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getReason())
            .isEqualTo("ADMIN_FORBIDDEN");
    }
}
