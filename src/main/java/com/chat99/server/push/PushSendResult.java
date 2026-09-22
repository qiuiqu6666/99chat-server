package com.chat99.server.push;

public record PushSendResult(boolean sent, boolean invalidToken, String detail) {

    public static PushSendResult ok() {
        return new PushSendResult(true, false, null);
    }

    public static PushSendResult skipped(String detail) {
        return new PushSendResult(false, false, detail);
    }

    public static PushSendResult failed(String detail) {
        return new PushSendResult(false, false, detail);
    }

    public static PushSendResult invalidToken(String detail) {
        return new PushSendResult(false, true, detail);
    }
}
