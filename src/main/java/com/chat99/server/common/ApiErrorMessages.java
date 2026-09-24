package com.chat99.server.common;

/**
 * App 接口错误码与面向用户的文案（JSON {@code message} 字段）。
 */
public final class ApiErrorMessages {

    public static final String CODE_ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    public static final String MSG_ACCOUNT_DISABLED = "该账号已禁用，请联系管理员";

    private ApiErrorMessages() {}

    public static String messageForCode(String code) {
        if (CODE_ACCOUNT_DISABLED.equals(code)) {
            return MSG_ACCOUNT_DISABLED;
        }
        if (code == null) {
            return "ERROR";
        }
        return switch (code) {
            case "UNAUTHORIZED" -> "请先登录";
            case "MOMENT_FORBIDDEN" -> "无权访问该内容";
            case "MOMENT_NOT_FOUND" -> "内容不存在";
            case "MOMENT_NOT_OWNER" -> "只能删除自己的动态";
            case "MOMENT_EMPTY_CONTENT" -> "文字和媒体不能同时为空";
            case "MOMENT_TEXT_TOO_LONG" -> "文字内容过长";
            case "MOMENT_MEDIA_TOO_MANY" -> "媒体数量过多";
            case "MOMENT_MEDIA_INVALID" -> "媒体不可用";
            case "MOMENT_COMMENT_EMPTY" -> "评论不能为空";
            case "MOMENT_COMMENT_TOO_LONG" -> "评论内容过长";
            case "MOMENT_COMMENT_NOT_FOUND" -> "评论不存在";
            case "MOMENT_SETTINGS_INVALID" -> "朋友圈可见范围设置无效";
            case "MOMENT_VISIBILITY_INVALID" -> "动态可见性设置无效";
            case "MOMENT_VISIBLE_USER_NOT_FRIEND" -> "可见用户必须是好友";
            case "MOMENT_COVER_UPLOAD_FAILED" -> "封面上传失败";
            case "INVALID_CURSOR" -> "分页参数无效";
            case "INVALID_PAGE_SIZE" -> "分页大小无效";
            case "INVALID_LOCATION" -> "定位参数无效";
            case "INVALID_LATITUDE" -> "纬度无效";
            case "INVALID_LONGITUDE" -> "经度无效";
            case "INVALID_ACCURACY" -> "定位精度无效";
            case "INVALID_HEADING" -> "航向无效";
            case "INVALID_SPEED" -> "速度无效";
            case "INVALID_SOURCE" -> "定位来源无效";
            case "INVALID_DEVICE_ID" -> "设备 ID 无效";
            case "INVALID_COLLECTED_AT" -> "采集时间无效";
            case "IDEMPOTENCY_CONFLICT" -> "重复请求参数不一致";
            case "ATTACHMENT_DISABLED" -> "大附件功能暂不可用";
            case "NATIVE_CHANNEL_REQUIRED" -> "该文件应走原生日志通道";
            case "FILE_TOO_LARGE" -> "文件过大";
            case "UNSUPPORTED_MEDIA_TYPE" -> "不支持的文件类型";
            case "QUOTA_EXCEEDED" -> "存储或上传配额已用尽";
            case "CONVERSATION_FORBIDDEN" -> "当前会话不允许发送该附件";
            case "UPLOAD_EXPIRED" -> "上传会话已过期";
            case "PART_MISSING" -> "分片不完整";
            case "CHECKSUM_MISMATCH" -> "文件校验失败";
            case "ATTACHMENT_NOT_READY" -> "附件尚未就绪";
            case "ATTACHMENT_GONE" -> "附件已失效";
            case "ACCESS_DENIED" -> "无权访问该附件";
            case "RATE_LIMITED" -> "请求过于频繁";
            case "STORAGE_UNAVAILABLE" -> "存储服务暂不可用";
            case "UPLOAD_FILE_TOO_LARGE" -> "上传文件过大";
            case "UPLOAD_TYPE_NOT_ALLOWED" -> "不支持的上传类型";
            case "service_disabled" -> "服务维护中";
            case "invalid_amount" -> "金额不支持";
            case "invalid_phone" -> "手机号格式错误";
            case "owner_last_char_required" -> "首次充值需要填写机主姓名最后一个字";
            case "provider_not_found" -> "缴费单位不存在";
            case "account_no_required" -> "户号不能为空";
            case "insufficient_platform_balance" -> "用户平台余额不足";
            case "pay_password_error" -> "平台支付密码错误";
            case "duplicate_client_order_id" -> "前端订单号重复";
            case "order_not_found" -> "订单不存在";
            case "order_cannot_cancel" -> "当前状态不能取消";
            case "LIVEKIT_NOT_CONFIGURED" -> "音视频服务未配置";
            case "LIVEKIT_WEBHOOK_UNAUTHORIZED" -> "回调鉴权失败";
            case "CANNOT_CALL_SELF" -> "不能呼叫自己";
            case "CALLEE_BUSY" -> "对方忙线中";
            case "NOT_FRIENDS" -> "仅好友可通话";
            case "NOT_CALLEE" -> "仅被叫可操作";
            case "NOT_CALLER" -> "仅主叫可操作";
            case "NOT_PARTICIPANT" -> "非通话参与者";
            case "CALL_NOT_FOUND" -> "通话不存在";
            case "CALL_ENDED" -> "通话已结束";
            case "CALL_NOT_RINGING" -> "通话不在振铃状态";
            case "CALL_ALREADY_ANSWERED" -> "通话已接听";
            case "INVALID_CALLEE" -> "被叫无效";
            case "INVALID_CALL_ID" -> "通话 ID 无效";
            case "FOLDER_NOT_FOUND" -> "分组不存在";
            case "FOLDER_LIMIT_EXCEEDED" -> "分组数量已达上限";
            case "FOLDER_SCOPE_IMMUTABLE" -> "分组类型不可修改";
            case "FOLDER_SCOPE_MISMATCH" -> "会话类型与分组不匹配";
            case "FOLDER_NAME_CONFLICT" -> "分组名称已存在";
            case "GROUP_JOIN_LIMIT_EXCEEDED" -> "部分用户加入群数量已达上限";
            case "GROUP_CREATE_LIMIT_COMMUNITY" -> "社群创建数量已达上限";
            case "COMMUNITY_PRICE_CHANGED" -> "超级大群价格已更新，请重新确认";
            case "COMMUNITY_PAYMENT_REQUIRED" -> "创建超级大群需付费";
            case "GROUP_CREATE_REQUEST_ID_REQUIRED", "GROUP_CREATE_REQUEST_ID_INVALID" -> "建群请求编号无效";
            case "GROUP_CREATE_REQUEST_CONFLICT" -> "建群请求编号冲突";
            case "PAY_PIN_REQUIRED" -> "请输入支付密码";
            case "CREATE_LIMIT_EXCEEDED" -> "建群数量已达上限";
            case "EXCHANGE_MAINTENANCE" -> "正在维护";
            case "USER_BLOCKED" -> "因拉黑无法添加好友";
            case "IM_UNAVAILABLE" -> "即时通讯服务暂不可用";
            default -> code;
        };
    }
}
