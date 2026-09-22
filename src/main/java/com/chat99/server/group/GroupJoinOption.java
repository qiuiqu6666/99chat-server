package com.chat99.server.group;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 加群 / 邀请入群方式（与腾讯 IM ApplyJoinOption / InviteJoinOption 对齐）。 */
public enum GroupJoinOption {
    /** 自动通过 */
    free_access,
    /** 需群主或管理员审批 */
    need_permission,
    /** 禁止（申请加群 → DisableApply；邀请入群 → DisableInvite） */
    disabled;

    public String imApplyValue() {
        return switch (this) {
            case free_access -> "FreeAccess";
            case need_permission -> "NeedPermission";
            case disabled -> "DisableApply";
        };
    }

    public String imInviteValue() {
        return switch (this) {
            case free_access -> "FreeAccess";
            case need_permission -> "NeedPermission";
            case disabled -> "DisableInvite";
        };
    }

    public static GroupJoinOption parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        try {
            return valueOf(raw.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    public static GroupJoinOption fromImApply(String imValue) {
        if (imValue == null || imValue.isBlank()) {
            return need_permission;
        }
        return switch (imValue.trim()) {
            case "FreeAccess" -> free_access;
            case "DisableApply" -> disabled;
            default -> need_permission;
        };
    }

    public static GroupJoinOption fromImInvite(String imValue) {
        if (imValue == null || imValue.isBlank()) {
            return need_permission;
        }
        return switch (imValue.trim()) {
            case "FreeAccess" -> free_access;
            case "DisableInvite" -> disabled;
            default -> need_permission;
        };
    }
}
