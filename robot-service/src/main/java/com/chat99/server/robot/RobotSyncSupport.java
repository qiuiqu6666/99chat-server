package com.chat99.server.robot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class RobotSyncSupport {

    /**
     * 中国时间业务日切：当天 07:00 之前算前一业务日。
     * 业务日标签 = 窗口起始日日历日；例 businessDate=2026-08-20 ⇒ [08-20 07:00, 08-21 07:00) CST。
     */
    public static final int BUSINESS_DAY_CUTOVER_HOURS = 7;
    public static final ZoneOffset CHINA_OFFSET = ZoneOffset.ofHours(8);

    public static final String EVENT_SNAPSHOT_UPSERT = "player.snapshot.upsert";
    public static final String EVENT_DAILY_SUMMARY = "player.daily.summary";
    public static final String EVENT_UPDOWN_RECORDED = "player.updown.recorded";
    public static final String EVENT_DATABASE_INITIALIZED = "robot.database.initialized";
    public static final String EVENT_FULL_SNAPSHOT_COMPLETED = "robot.full_snapshot.completed";

    public static final String REASON_DATABASE_RESET = "database_reset";
    public static final String REASON_FULL_REBUILD_COMPLETED = "full_rebuild_completed";

    private RobotSyncSupport() {}

    public static LocalDate resolveBusinessDate(long businessTimestamp, String businessTimezone) {
        if (businessTimezone == null || businessTimezone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "businessTimezone is required");
        }
        try {
            ZoneOffset offset = ZoneOffset.of(businessTimezone);
            return Instant.ofEpochSecond(businessTimestamp)
                .atOffset(offset)
                .minusHours(BUSINESS_DAY_CUTOVER_HOURS)
                .toLocalDate();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid businessTimezone");
        }
    }

    /** App 查询某一业务日时，中国时间窗口起点（当天 07:00 CST）的 epoch 秒。 */
    public static long businessDayStartEpoch(LocalDate date) {
        if (date == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date is required");
        }
        return date.atTime(BUSINESS_DAY_CUTOVER_HOURS, 0).atOffset(CHINA_OFFSET).toEpochSecond();
    }

    /** 当前时刻所属业务日标签（中国时间；07:00 前仍属前一日）。 */
    public static LocalDate currentBusinessDate() {
        return Instant.now().atOffset(CHINA_OFFSET)
            .minusHours(BUSINESS_DAY_CUTOVER_HOURS)
            .toLocalDate();
    }

    public static void validateEventType(String eventType) {
        if (!EVENT_SNAPSHOT_UPSERT.equals(eventType)
            && !EVENT_DAILY_SUMMARY.equals(eventType)
            && !EVENT_UPDOWN_RECORDED.equals(eventType)
            && !EVENT_DATABASE_INITIALIZED.equals(eventType)
            && !EVENT_FULL_SNAPSHOT_COMPLETED.equals(eventType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported eventType");
        }
    }

    public static boolean isControlEvent(String eventType) {
        return EVENT_DATABASE_INITIALIZED.equals(eventType)
            || EVENT_FULL_SNAPSHOT_COMPLETED.equals(eventType);
    }

    public static String resolveRobotId(RobotSyncRequest request) {
        if (request.getRobotId() != null && !request.getRobotId().isBlank()) {
            return request.getRobotId().trim();
        }
        if (request.getPlayerGroupId() != null && !request.getPlayerGroupId().isBlank()) {
            return request.getPlayerGroupId().trim();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "robotId must not be blank");
    }

    public static void validateControlEvent(RobotSyncRequest request) {
        String robotId = resolveRobotId(request);
        if (request.getDatabaseGeneration() == null || request.getDatabaseGeneration().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "databaseGeneration must not be blank");
        }
        if (request.getEntityId() == null || request.getEntityId().isBlank()) {
            // 控制事件 entityId 可缺省；鉴权只认密钥，不再强制 entityId == robotId
            request.setEntityId(robotId);
        }
        if (request.getRobotId() == null || request.getRobotId().isBlank()) {
            request.setRobotId(robotId);
        }
        if (request.getPlayerGroupId() == null || request.getPlayerGroupId().isBlank()) {
            request.setPlayerGroupId(robotId);
        }
    }
}
