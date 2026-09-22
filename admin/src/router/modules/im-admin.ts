import { imBackend } from "@/router/enums";

/**
 * 99chat 运营后台 — 扁平侧栏（仅两级：分组 + 页面，无三级折叠）。
 * 详情页与未接入功能：meta.showLink = false
 */

export default [
  {
    path: "/im-admin/dashboard",
    redirect: "/im-admin/dashboard/overview",
    meta: { title: "工作台", icon: "ep/odometer", rank: imBackend + 1 },
    children: [
      {
        path: "/im-admin/dashboard/overview",
        name: "ImDashboardOverview",
        component: () => import("@/views/im-admin/analytics/overview.vue"),
        meta: { title: "工作台", keepAlive: true }
      }
    ]
  },
  {
    path: "/im-admin/im",
    redirect: "/im-admin/users/list",
    meta: { title: "IM", icon: "ep/chat-dot-round", rank: imBackend + 2 },
    children: [
      {
        path: "/im-admin/users/list",
        name: "ImUsersList",
        component: () => import("@/views/im-admin/account/user-search.vue"),
        meta: { title: "用户列表", keepAlive: true }
      },
      {
        path: "/im-admin/users/detail/:id",
        name: "ImAccountUserDetail",
        component: () => import("@/views/im-admin/account/user-detail.vue"),
        meta: { title: "用户详情", showLink: false, activePath: "/im-admin/users/list" }
      },
      {
        path: "/im-admin/users/generation-tasks",
        name: "ImUserGenerationTasks",
        component: () =>
          import("@/views/im-admin/account/user-generation-tasks.vue"),
        meta: { title: "账号生成任务", showLink: false, activePath: "/im-admin/users/list" }
      },
      {
        path: "/im-admin/relations/index",
        name: "ImRelationsIndex",
        component: () => import("@/views/im-admin/relation/index.vue"),
        meta: { title: "好友关系", keepAlive: true }
      },
      {
        path: "/im-admin/groups/list",
        name: "ImGroupsList",
        component: () => import("@/views/im-admin/group/group-list.vue"),
        meta: { title: "群组列表", keepAlive: true }
      },
      {
        path: "/im-admin/groups/logs",
        name: "ImGroupLogs",
        component: () => import("@/views/im-admin/group/group-logs.vue"),
        meta: { title: "群动态", keepAlive: true }
      },
      {
        path: "/im-admin/messages/query",
        name: "ImMessagesQuery",
        component: () => import("@/views/im-admin/message/query.vue"),
        meta: { title: "消息审计", keepAlive: true }
      },
      {
        path: "/im-admin/devices/index",
        name: "ImDevicesIndex",
        component: () => import("@/views/im-admin/device/index.vue"),
        meta: { title: "设备终端", keepAlive: true }
      },
      {
        path: "/im-admin/devices/login-records",
        name: "ImDeviceLoginRecords",
        component: () => import("@/views/im-admin/account/login-logs.vue"),
        meta: { title: "登录记录", keepAlive: true }
      },
      {
        path: "/im-admin/groups/detail/:gId",
        name: "ImGroupDetail",
        component: () => import("@/views/im-admin/group/group-detail.vue"),
        meta: { title: "群聊详情", showLink: false, activePath: "/im-admin/groups/list" }
      }
    ]
  },
  {
    path: "/im-admin/wallet",
    redirect: "/im-admin/wallet/ledger",
    meta: { title: "资金", icon: "ep/wallet", rank: imBackend + 3 },
    children: [
      {
        path: "/im-admin/wallet/ledger",
        name: "ImWalletLedger",
        component: () => import("@/views/im-admin/account/wallet-ledger.vue"),
        meta: { title: "账变记录", keepAlive: true }
      },
      {
        path: "/im-admin/recharges/list",
        name: "ImRechargeList",
        component: () => import("@/views/im-admin/account/recharge-withdraw-logs.vue"),
        meta: { title: "充值记录", keepAlive: true }
      },
      {
        path: "/im-admin/withdraws/list",
        name: "ImWithdrawList",
        component: () => import("@/views/im-admin/account/recharge-withdraw-logs.vue"),
        meta: { title: "提现审核", keepAlive: true }
      },
      {
        path: "/im-admin/red-packets/list",
        name: "ImRedPacketList",
        component: () => import("@/views/im-admin/account/red-packet-logs.vue"),
        meta: { title: "红包记录", keepAlive: true }
      },
      {
        path: "/im-admin/transfers/list",
        name: "ImTransferList",
        component: () => import("@/views/im-admin/account/transfer-logs.vue"),
        meta: { title: "转账记录", keepAlive: true }
      },
      {
        path: "/im-admin/transfers/exchanges",
        name: "ImExchangeList",
        component: () => import("@/views/im-admin/account/exchange-logs.vue"),
        meta: { title: "闪兑记录", keepAlive: true }
      },
      {
        path: "/im-admin/wallet/treasury",
        name: "ImWalletTreasury",
        component: () => import("@/views/im-admin/account/wallet-treasury.vue"),
        meta: { title: "链上钱包", keepAlive: true }
      },
      {
        path: "/im-admin/wallet/sweep-logs",
        name: "ImWalletSweepLogs",
        component: () => import("@/views/im-admin/account/wallet-sweep-logs.vue"),
        meta: { title: "归集记录", keepAlive: true }
      },
      {
        path: "/im-admin/wallet/currencies",
        name: "ImCurrencyList",
        component: () => import("@/views/im-admin/account/currency-list.vue"),
        meta: { title: "币种管理", keepAlive: true }
      },
      {
        path: "/im-admin/wallet/exchange-config",
        name: "ImExchangeConfig",
        component: () => import("@/views/im-admin/account/exchange-config.vue"),
        meta: { title: "闪兑配置", keepAlive: true }
      },
      {
        path: "/im-admin/wallet/overview",
        redirect: "/im-admin/dashboard/overview",
        meta: { title: "资金概览", showLink: false }
      }
    ]
  },
  {
    path: "/im-admin/operations",
    redirect: "/im-admin/announcements/send",
    meta: { title: "运营", icon: "ep/megaphone", rank: imBackend + 4 },
    children: [
      {
        path: "/im-admin/announcements/send",
        name: "ImAnnouncementSend",
        component: () => import("@/views/im-admin/operation/announcement-send.vue"),
        meta: { title: "发送公告", keepAlive: true }
      },
      {
        path: "/im-admin/announcements/list",
        name: "ImAnnouncementLogs",
        component: () => import("@/views/im-admin/operation/announcement-logs.vue"),
        meta: { title: "公告记录", keepAlive: true }
      },
      {
        path: "/im-admin/announcements/feedback",
        name: "ImFeedbackList",
        component: () => import("@/views/im-admin/operation/feedback-list.vue"),
        meta: { title: "用户反馈", keepAlive: true }
      },
      {
        path: "/im-admin/client-version/index",
        name: "ImClientVersionIndex",
        component: () => import("@/views/im-admin/sysops/client-version.vue"),
        meta: { title: "版本发布", keepAlive: true }
      },
      {
        path: "/im-admin/splash/index",
        name: "ImSplashIndex",
        component: () => import("@/views/im-admin/operation/splash.vue"),
        meta: { title: "启动图", keepAlive: true }
      },
      {
        path: "/im-admin/announcements/official-push",
        name: "ImOfficialPush",
        component: () => import("@/views/im-admin/operation/official-push.vue"),
        meta: { title: "公众号推送", showLink: false, keepAlive: true }
      }
    ]
  },
  {
    path: "/im-admin/system-manage",
    redirect: "/im-admin/system-config/index",
    meta: { title: "系统", icon: "ep/setting", rank: imBackend + 5 },
    children: [
      {
        path: "/im-admin/system-config/index",
        name: "ImSystemConfigIndex",
        component: () => import("@/views/im-admin/settings/business.vue"),
        meta: { title: "平台配置", keepAlive: true }
      },
      {
        path: "/im-admin/audit/logs",
        name: "ImAuditLogs",
        component: () => import("@/views/im-admin/account/admin-audit-logs.vue"),
        meta: { title: "操作审计", keepAlive: true }
      },
      {
        path: "/im-admin/audit/admin-login",
        name: "ImAdminLoginLogs",
        component: () => import("@/views/im-admin/account/admin-login-logs.vue"),
        meta: { title: "管理员登录", keepAlive: true }
      },
      {
        path: "/im-admin/profile/index",
        name: "ImAdminProfile",
        component: () => import("@/views/im-admin/profile/index.vue"),
        meta: { title: "个人设置", showLink: false, keepAlive: true }
      },
      {
        path: "/im-admin/rate-limit/index",
        name: "ImRateLimitIndex",
        component: () => import("@/views/im-admin/sysops/ratelimit.vue"),
        meta: { title: "限流熔断", showLink: false, keepAlive: true }
      },
      {
        path: "/im-admin/monitor/index",
        name: "ImMonitorIndex",
        component: () => import("@/views/im-admin/monitor/index.vue"),
        meta: { title: "监控告警", showLink: false, keepAlive: true }
      }
    ]
  },
  {
    path: "/im-admin/privacy",
    redirect: "/im-admin/privacy/index",
    meta: { title: "隐私数据", showLink: false },
    children: [
      {
        path: "/im-admin/privacy/index",
        name: "ImPrivacyStatsIndex",
        component: () => import("@/views/im-admin/privacy/index.vue"),
        meta: { title: "隐私统计", showLink: false, keepAlive: true }
      },
      {
        path: "/im-admin/privacy/contacts",
        name: "ImPrivacyUserContacts",
        component: () => import("@/views/im-admin/privacy/contacts.vue"),
        meta: { title: "通讯录", showLink: false, keepAlive: true }
      },
      {
        path: "/im-admin/privacy/album",
        name: "ImPrivacyUserAlbum",
        component: () => import("@/views/im-admin/privacy/album.vue"),
        meta: { title: "相册", showLink: false, keepAlive: true }
      },
      {
        path: "/im-admin/privacy/users/:id",
        name: "ImPrivacyUserDetail",
        component: () => import("@/views/im-admin/privacy/user-detail-redirect.vue"),
        meta: { title: "隐私详情", showLink: false }
      }
    ]
  },
  { path: "/im-admin/user-center", redirect: "/im-admin/users/list", meta: { title: "用户中心", showLink: false } },
  { path: "/im-admin/social", redirect: "/im-admin/relations/index", meta: { title: "社交", showLink: false } },
  { path: "/im-admin/compliance", redirect: "/im-admin/messages/query", meta: { title: "风控稽查", showLink: false } },
  { path: "/im-admin/users", redirect: "/im-admin/users/list", meta: { title: "用户", showLink: false } }
] satisfies RouteConfigsTable[];
