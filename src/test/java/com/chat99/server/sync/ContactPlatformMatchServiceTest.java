package com.chat99.server.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chat99.server.common.PhoneUtils;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContactPlatformMatchServiceTest {

    @Test
    void matchesRegisteredPhoneAndExcludesOwner() {
        UserRepository users = mock(UserRepository.class);
        User registered = new User();
        registered.setUserId("friend001");
        registered.setPhone("+8613812345678");
        when(users.findByPhoneIn(anyCollection())).thenReturn(List.of(registered));
        ContactPlatformMatchService service = new ContactPlatformMatchService(
            users, new PhoneUtils(), new ObjectMapper());

        Map<String, ContactPlatformMatchService.Match> matches = service.match(List.of(
            new ContactPlatformMatchService.ContactCandidate(
                "friend", "owner001", List.of("138 1234 5678")),
            new ContactPlatformMatchService.ContactCandidate(
                "self", "friend001", List.of("+8613812345678")),
            new ContactPlatformMatchService.ContactCandidate(
                "invalid", "owner001", List.of("not-a-phone"))));

        assertTrue(matches.get("friend").platformUser());
        assertEquals("friend001", matches.get("friend").matchedUserId());
        assertFalse(matches.get("self").platformUser());
        assertFalse(matches.get("invalid").platformUser());
    }
}
