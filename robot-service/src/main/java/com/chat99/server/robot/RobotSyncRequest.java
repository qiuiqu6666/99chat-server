package com.chat99.server.robot;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class RobotSyncRequest {

    @NotBlank
    private String protocolVersion;

    @NotBlank
    private String eventId;

    @NotBlank
    private String eventType;

    /** Optional for legacy player events; required for robot control events. */
    private String robotId;

    /** Optional for legacy player events; required for robot control events. */
    private String databaseGeneration;

    @NotBlank
    private String playerGroupId;

    @NotBlank
    private String statisticsGroupId;

    @NotBlank
    private String entityId;

    @NotNull
    private Long businessTimestamp;

    @NotBlank
    private String businessTimezone;

    @NotNull
    private Long sourceUpdatedAt;

    @NotBlank
    private String syncReason;

    @NotNull
    private JsonNode data;

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getRobotId() {
        return robotId;
    }

    public void setRobotId(String robotId) {
        this.robotId = robotId;
    }

    public String getDatabaseGeneration() {
        return databaseGeneration;
    }

    public void setDatabaseGeneration(String databaseGeneration) {
        this.databaseGeneration = databaseGeneration;
    }

    public String getPlayerGroupId() {
        return playerGroupId;
    }

    public void setPlayerGroupId(String playerGroupId) {
        this.playerGroupId = playerGroupId;
    }

    public String getStatisticsGroupId() {
        return statisticsGroupId;
    }

    public void setStatisticsGroupId(String statisticsGroupId) {
        this.statisticsGroupId = statisticsGroupId;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public Long getBusinessTimestamp() {
        return businessTimestamp;
    }

    public void setBusinessTimestamp(Long businessTimestamp) {
        this.businessTimestamp = businessTimestamp;
    }

    public String getBusinessTimezone() {
        return businessTimezone;
    }

    public void setBusinessTimezone(String businessTimezone) {
        this.businessTimezone = businessTimezone;
    }

    public Long getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public void setSourceUpdatedAt(Long sourceUpdatedAt) {
        this.sourceUpdatedAt = sourceUpdatedAt;
    }

    public String getSyncReason() {
        return syncReason;
    }

    public void setSyncReason(String syncReason) {
        this.syncReason = syncReason;
    }

    public JsonNode getData() {
        return data;
    }

    public void setData(JsonNode data) {
        this.data = data;
    }
}
