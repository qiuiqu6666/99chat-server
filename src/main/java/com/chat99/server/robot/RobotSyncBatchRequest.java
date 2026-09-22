package com.chat99.server.robot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 增量批次 envelope；与单事件共用同一 URL。 */
public class RobotSyncBatchRequest {

    public static final int MAX_EVENTS = 200;

    @NotBlank
    private String protocolVersion;

    @NotNull
    private Boolean batch;

    @NotBlank
    private String batchId;

    @NotNull
    @Min(1)
    @Max(8)
    private Integer lane;

    @NotNull
    @Min(1)
    @Max(MAX_EVENTS)
    private Integer eventCount;

    @NotEmpty
    @Valid
    private List<RobotSyncRequest> events;

    @AssertTrue(message = "batch must be true")
    public boolean isBatchFlagTrue() {
        return Boolean.TRUE.equals(batch);
    }

    @AssertTrue(message = "eventCount must equal events.size")
    public boolean isEventCountMatched() {
        return eventCount != null && events != null && eventCount == events.size();
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public Boolean getBatch() {
        return batch;
    }

    public void setBatch(Boolean batch) {
        this.batch = batch;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public Integer getLane() {
        return lane;
    }

    public void setLane(Integer lane) {
        this.lane = lane;
    }

    public Integer getEventCount() {
        return eventCount;
    }

    public void setEventCount(Integer eventCount) {
        this.eventCount = eventCount;
    }

    public List<RobotSyncRequest> getEvents() {
        return events;
    }

    public void setEvents(List<RobotSyncRequest> events) {
        this.events = events;
    }
}
