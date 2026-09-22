package com.chat99.server.call;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class TrtcCallResultMapper {

    private TrtcCallResultMapper() {}

    public static CallRecordResult toRecordResult(String callResult) {
        if (callResult == null || callResult.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
        }
        return switch (callResult.trim().toLowerCase().replace('_', ' ').replace("-", " ")) {
            case "normal end", "normalend" -> CallRecordResult.ANSWERED;
            case "not answer", "notanswer" -> CallRecordResult.MISSED;
            case "reject" -> CallRecordResult.REJECTED;
            case "cancel" -> CallRecordResult.CANCELED;
            case "call busy", "callbusy" -> CallRecordResult.BUSY;
            case "interrupt", "offline" -> CallRecordResult.FAILED;
            default -> CallRecordResult.FAILED;
        };
    }

    public static String normalizeMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return "audio";
        }
        return switch (mediaType.trim().toLowerCase()) {
            case "video" -> "video";
            default -> "audio";
        };
    }

    public static String normalizeCallType(String callType) {
        if (callType == null || callType.isBlank()) {
            return null;
        }
        return switch (callType.trim().toLowerCase()) {
            case "single", "singlecall" -> "single";
            case "group", "multcall", "multicall" -> "group";
            default -> callType.trim().toLowerCase();
        };
    }

    public static boolean isSingleCall(String callType) {
        return "single".equals(normalizeCallType(callType));
    }

    /**
     * 推导「谁结束了通话」的原始 userId，供聊天气泡精确渲染方向 / 文案。
     *
     * <ul>
     *   <li>{@code CANCELED}（主叫接通前取消）→ 主叫</li>
     *   <li>{@code REJECTED}（被叫拒接）/ {@code MISSED}（超时未接）/ {@code BUSY}（忙线）→ 被叫</li>
     *   <li>{@code ANSWERED}（正常结束，展示时长）/ {@code FAILED}（Offline，服务端结束）→ 无关键操作者</li>
     * </ul>
     */
    public static String resolveOperatorUserId(CallRecordResult result, String callerUserId, String calleeUserId) {
        if (result == null) {
            return null;
        }
        return switch (result) {
            case CANCELED -> callerUserId;
            case REJECTED, MISSED, BUSY -> calleeUserId;
            case ANSWERED, FAILED -> null;
        };
    }

    public static CallSessionStatus toSessionStatus(CallRecordResult result) {
        return switch (result) {
            case ANSWERED -> CallSessionStatus.ANSWERED;
            case MISSED -> CallSessionStatus.MISSED;
            case REJECTED -> CallSessionStatus.REJECTED;
            case CANCELED -> CallSessionStatus.CANCELED;
            case BUSY -> CallSessionStatus.BUSY;
            case FAILED -> CallSessionStatus.FAILED;
        };
    }
}
