package com.chat99.server.user;

/** 系统通知栏展示内容（App 未打开时）。 */
public enum NotificationDisplayContent {
    /** 显示朋友名称、群聊名及消息内容 */
    show_all,
    /** 仅显示「你收到了一条消息」 */
    generic,
    /** 隐藏朋友名称、群聊名及消息内容（仅 App 名称） */
    hidden
}
