package com.chat99.server.user;

import java.time.Instant;

public class NicknameCooldownException extends RuntimeException {

    private final Instant nextChangeableAt;

    public NicknameCooldownException(Instant nextChangeableAt) {
        super("NICKNAME_COOLDOWN");
        this.nextChangeableAt = nextChangeableAt;
    }

    public Instant nextChangeableAt() {
        return nextChangeableAt;
    }
}
