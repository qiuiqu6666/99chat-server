package com.chat99.server.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FriendApplicationServiceTest {

    @Mock
    private FriendApplicationRepository repository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private FriendApplicationService service;

    @Test
    void deleteHistory_removesOwnedRecord() {
        FriendApplicationHistory row = new FriendApplicationHistory();
        row.setId(9L);
        row.setUserId("user1");
        when(repository.softDelete("user1", 9L)).thenReturn(1);

        var result = service.deleteHistory("user1", 9L);

        verify(repository).softDelete("user1", 9L);
        assertEquals(true, result.get("ok"));
        assertEquals(9L, result.get("id"));
    }

    @Test
    void deleteHistory_rejectsForeignRecord() {
        when(repository.softDelete("user1", 9L)).thenReturn(0);

        ResponseStatusException ex = assertThrows(
            ResponseStatusException.class, () -> service.deleteHistory("user1", 9L));
        assertEquals("RECORD_NOT_FOUND", ex.getReason());
    }
}
