package com.chat99.server.im;

public class ImRestException extends RuntimeException {

    private final int imErrorCode;

    public ImRestException(String message, int imErrorCode) {
        super(message);
        this.imErrorCode = imErrorCode;
    }

    public int imErrorCode() {
        return imErrorCode;
    }
}
