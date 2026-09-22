package com.chat99.server.robot;

public class RobotSyncDuplicateEventException extends RuntimeException {

    private final String eventId;

    public RobotSyncDuplicateEventException(String eventId) {
        super("duplicate event: " + eventId);
        this.eventId = eventId;
    }

    public String getEventId() {
        return eventId;
    }
}
