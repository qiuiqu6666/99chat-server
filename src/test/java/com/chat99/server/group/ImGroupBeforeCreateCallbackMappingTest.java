package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImCallbackVerifier;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImGroupBeforeCreateCallbackMappingTest {

    @Mock GroupCreateLimitConfigService config;
    @Mock UserOwnedGroupService ownedGroupService;
    @Mock GroupJoinLimitService joinLimitService;
    @Mock ImUserIdService imUserIdService;
    @Mock ImCallbackVerifier callbackVerifier;

    ImGroupBeforeCreateCallbackService service;

    @BeforeEach
    void setUp() {
        service = new ImGroupBeforeCreateCallbackService(
            config, ownedGroupService, joinLimitService, imUserIdService, callbackVerifier, new ObjectMapper());
        when(config.isEnabled()).thenReturn(true);
        when(config.isLogOnly()).thenReturn(false);
        lenient().when(config.isEnforce()).thenReturn(true);
        when(imUserIdService.toBusinessForDisplay("q14gkm5swv")).thenReturn("q14gkm5swv");
        when(imUserIdService.toBusinessForDisplayBatch(anySet()))
            .thenReturn(Map.of("q14gkm5swv", "q14gkm5swv"));
        when(imUserIdService.isSpecialImAccount(any())).thenReturn(false);
        when(joinLimitService.findOverLimitUsers(any(), any())).thenReturn(List.of());
    }

    @Test
    void handle_usesBusinessUserIdDirectly() {
        when(ownedGroupService.canCreate("q14gkm5swv", "Community")).thenReturn(true);

        String body = """
            {
              "Owner_Account": "q14gkm5swv",
              "Type": "Community",
              "MemberList": []
            }
            """;

        var response = service.handle(
            "123", ImGroupBeforeCreateCallbackService.CMD_GROUP_BEFORE_CREATE,
            "token", null, null, body);

        assertThat(response).isNotNull();
        assertThat(response.ErrorCode()).isEqualTo(0);
        verify(ownedGroupService).canCreate("q14gkm5swv", "Community");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> candidates = ArgumentCaptor.forClass(Set.class);
        verify(joinLimitService).findOverLimitUsers(candidates.capture(), eq("Community"));
        assertThat(candidates.getValue()).containsExactly("q14gkm5swv");
    }
}
