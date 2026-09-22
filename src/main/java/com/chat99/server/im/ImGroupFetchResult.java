package com.chat99.server.im;

import com.chat99.server.im.ImAdminClient.GroupAdminInfo;

/**
 * 群资料拉取结果：区分「真不存在」与「配额/瞬态失败」，避免误标解散。
 */
public record ImGroupFetchResult(Status status, GroupAdminInfo info, Integer errorCode) {

    public enum Status {
        OK,
        NOT_FOUND,
        RATE_LIMITED,
        ERROR,
        NOT_CONFIGURED
    }

    public static ImGroupFetchResult ok(GroupAdminInfo info) {
        return new ImGroupFetchResult(Status.OK, info, 0);
    }

    public static ImGroupFetchResult notFound(Integer errorCode) {
        return new ImGroupFetchResult(Status.NOT_FOUND, null, errorCode);
    }

    public static ImGroupFetchResult rateLimited(Integer errorCode) {
        return new ImGroupFetchResult(Status.RATE_LIMITED, null, errorCode);
    }

    public static ImGroupFetchResult error(Integer errorCode) {
        return new ImGroupFetchResult(Status.ERROR, null, errorCode);
    }

    public static ImGroupFetchResult notConfigured() {
        return new ImGroupFetchResult(Status.NOT_CONFIGURED, null, null);
    }

    public boolean isOk() {
        return status == Status.OK && info != null;
    }

    public boolean isDefinitelyGone() {
        return status == Status.NOT_FOUND;
    }

    public boolean isTransientFailure() {
        return status == Status.RATE_LIMITED || status == Status.ERROR || status == Status.NOT_CONFIGURED;
    }
}
