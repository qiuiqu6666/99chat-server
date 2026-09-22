package com.chat99.server.user;

public class SearchBlockedException extends RuntimeException {

    public final long retryAfterSeconds;

    public SearchBlockedException(long retryAfterSeconds) {
        super("search blocked");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
