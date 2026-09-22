package com.chat99.server.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RobotSyncSupportTest {

    @Test
    void resolveBusinessDateWithPlusEightTimezone() {
        // 2024-08-20 08:00 +08 → 减 7 小时仍是 20 日
        var date = RobotSyncSupport.resolveBusinessDate(1_724_112_000L, "+08:00");
        assertThat(date).isEqualTo(java.time.LocalDate.of(2024, 8, 20));
    }

    @Test
    void chinaBusinessDayCutsOverAtSevenAm() {
        // 2026-08-19 06:59:00 +08 = 1787093940 → 业务日 18
        assertThat(RobotSyncSupport.resolveBusinessDate(1_787_093_940L, "+08:00"))
            .isEqualTo(java.time.LocalDate.of(2026, 8, 18));
        // 2026-08-19 07:00:00 +08 = 1787094000 → 业务日 19
        assertThat(RobotSyncSupport.resolveBusinessDate(1_787_094_000L, "+08:00"))
            .isEqualTo(java.time.LocalDate.of(2026, 8, 19));
        // 2026-08-20 06:59:00 +08 = 1787180340 → 业务日 19
        assertThat(RobotSyncSupport.resolveBusinessDate(1_787_180_340L, "+08:00"))
            .isEqualTo(java.time.LocalDate.of(2026, 8, 19));
        // 2026-08-20 07:00:00 +08 = 1787180400 → 业务日 20
        assertThat(RobotSyncSupport.resolveBusinessDate(1_787_180_400L, "+08:00"))
            .isEqualTo(java.time.LocalDate.of(2026, 8, 20));
    }

    @Test
    void businessDayStartEpochIsChinaSevenAm() {
        assertThat(RobotSyncSupport.businessDayStartEpoch(java.time.LocalDate.of(2026, 8, 19)))
            .isEqualTo(1_787_094_000L);
        assertThat(RobotSyncSupport.businessDayStartEpoch(java.time.LocalDate.of(2026, 8, 20)))
            .isEqualTo(1_787_180_400L);
    }

    @Test
    void rejectInvalidTimezone() {
        assertThatThrownBy(() -> RobotSyncSupport.resolveBusinessDate(1_724_006_400L, "invalid"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectUnsupportedEventType() {
        assertThatThrownBy(() -> RobotSyncSupport.validateEventType("player.deleted"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptControlEventTypes() {
        RobotSyncSupport.validateEventType(RobotSyncSupport.EVENT_DATABASE_INITIALIZED);
        RobotSyncSupport.validateEventType(RobotSyncSupport.EVENT_FULL_SNAPSHOT_COMPLETED);
    }

    @Test
    void acceptUpdownRecordedEventType() {
        RobotSyncSupport.validateEventType(RobotSyncSupport.EVENT_UPDOWN_RECORDED);
    }
}
