package com.chat99.sangong.common;

public class BusinessException extends RuntimeException {
    private final String code;
    private final int httpStatus;
    public BusinessException(String code, String message) { this(code, message, 422); }
    public BusinessException(String code, String message, int httpStatus) {
        super(message); this.code = code; this.httpStatus = httpStatus;
    }
    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
}
