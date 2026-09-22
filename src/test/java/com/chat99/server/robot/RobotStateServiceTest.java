package com.chat99.server.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RobotStateServiceTest {

    @Mock
    private RobotRuntimeStateRepository repository;

    private RobotStateService service;

    @BeforeEach
    void setUp() {
        service = new RobotStateService(repository);
    }

    @Test
    void requireReadyPassesWhenReady() {
        when(repository.findByRobotId("robot-1")).thenReturn(Optional.of(
            new RobotRuntimeState("robot-1", "gen-9", "READY", 0, null, null, null)));

        var state = service.requireReady("robot-1", "gen-9");
        assertThat(state.databaseGeneration()).isEqualTo("gen-9");
    }

    @Test
    void requireReadyRejectsSyncing() {
        when(repository.findByRobotId("robot-1")).thenReturn(Optional.of(
            new RobotRuntimeState("robot-1", "gen-9", "SYNCING", 0, null, null, null)));

        assertThatThrownBy(() -> service.requireReady("robot-1"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void requireReadyRejectsStaleGeneration() {
        when(repository.findByRobotId("robot-1")).thenReturn(Optional.of(
            new RobotRuntimeState("robot-1", "gen-9", "READY", 0, null, null, null)));

        assertThatThrownBy(() -> service.requireReady("robot-1", "old-gen"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("STALE_DATABASE_GENERATION"));
    }
}
