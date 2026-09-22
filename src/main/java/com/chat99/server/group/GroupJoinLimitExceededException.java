package com.chat99.server.group;

import java.util.List;

/**
 * 用户加入群数量超限；由 {@link com.chat99.server.common.GlobalExceptionHandler} 转成 403 + overLimitUsers。
 */
public class GroupJoinLimitExceededException extends RuntimeException {

    private final String code;
    private final List<OverLimitUser> overLimitUsers;

    public GroupJoinLimitExceededException(String code, List<OverLimitUser> overLimitUsers) {
        super(code);
        this.code = code == null ? "GROUP_JOIN_LIMIT_EXCEEDED" : code;
        this.overLimitUsers = overLimitUsers == null ? List.of() : List.copyOf(overLimitUsers);
    }

    public String getCode() {
        return code;
    }

    public List<OverLimitUser> getOverLimitUsers() {
        return overLimitUsers;
    }

    public record OverLimitUser(String userId, int used, int max, String limitType) {}
}
