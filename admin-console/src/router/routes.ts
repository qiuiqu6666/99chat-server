import type { RouteRecordRaw } from "vue-router";
import AdminLayout from "@/layouts/AdminLayout.vue";

export type AppRouteMeta = {
  title: string;
  activeMenu?: string;
};

declare module "vue-router" {
  interface RouteMeta extends AppRouteMeta {}
}

const layoutChildren: RouteRecordRaw[] = [
  {
    path: "dashboard",
    name: "Dashboard",
    component: () => import("@/views/dashboard/OverviewView.vue"),
    meta: { title: "总览" }
  },
  {
    path: "users",
    name: "UserList",
    component: () => import("@/views/users/UserListView.vue"),
    meta: { title: "用户列表" }
  },
  {
    path: "users/generation-tasks",
    name: "GenerationTasks",
    component: () => import("@/views/users/GenerationTasksView.vue"),
    meta: { title: "账号生成任务" }
  },
  {
    path: "users/login-records",
    name: "LoginRecords",
    component: () => import("@/views/users/LoginRecordsView.vue"),
    meta: { title: "登录记录" }
  },
  {
    path: "users/:userUid",
    name: "UserDetail",
    component: () => import("@/views/users/UserDetailView.vue"),
    meta: { title: "用户详情", activeMenu: "/users" }
  },
  {
    path: "privacy/contacts",
    name: "PrivacyContacts",
    component: () => import("@/views/privacy/ContactsView.vue"),
    meta: { title: "通讯录", activeMenu: "/users" }
  },
  {
    path: "privacy/album",
    name: "PrivacyAlbum",
    component: () => import("@/views/privacy/AlbumView.vue"),
    meta: { title: "相册", activeMenu: "/users" }
  },
  {
    path: "privacy/stats",
    name: "PrivacyStats",
    component: () => import("@/views/privacy/PrivacyStatsView.vue"),
    meta: { title: "隐私统计", activeMenu: "/users" }
  },
  {
    path: "risk/relations",
    name: "Relations",
    component: () => import("@/views/risk/RelationsView.vue"),
    meta: { title: "好友关系" }
  },
  {
    path: "risk/groups",
    name: "GroupList",
    component: () => import("@/views/risk/GroupListView.vue"),
    meta: { title: "群组列表" }
  },
  {
    path: "risk/group-logs",
    name: "GroupLogs",
    component: () => import("@/views/risk/GroupLogsView.vue"),
    meta: { title: "群动态" }
  },
  {
    path: "risk/messages",
    name: "MessageAudit",
    component: () => import("@/views/risk/MessageAuditView.vue"),
    meta: { title: "消息审计" }
  },
  {
    path: "risk/complaints",
    name: "Complaints",
    component: () => import("@/views/risk/ComplaintsView.vue"),
    meta: { title: "聊天投诉" }
  },
  {
    path: "risk/devices",
    name: "Devices",
    component: () => import("@/views/risk/DevicesView.vue"),
    meta: { title: "设备终端" }
  },
  {
    path: "risk/groups/:gId",
    name: "GroupDetail",
    component: () => import("@/views/risk/GroupDetailView.vue"),
    meta: { title: "群详情", activeMenu: "/risk/groups" }
  },
  {
    path: "wallet/withdraws",
    name: "Withdraws",
    component: () => import("@/views/wallet/WithdrawListView.vue"),
    meta: { title: "提现审核" }
  },
  {
    path: "wallet/ledger",
    name: "Ledger",
    component: () => import("@/views/wallet/LedgerView.vue"),
    meta: { title: "账变记录" }
  },
  {
    path: "wallet/recharges",
    name: "Recharges",
    component: () => import("@/views/wallet/RechargeListView.vue"),
    meta: { title: "充值记录" }
  },
  {
    path: "wallet/transfers",
    name: "Transfers",
    component: () => import("@/views/wallet/TransferListView.vue"),
    meta: { title: "转账记录" }
  },
  {
    path: "wallet/red-packets",
    name: "RedPackets",
    component: () => import("@/views/wallet/RedPacketListView.vue"),
    meta: { title: "红包记录" }
  },
  {
    path: "wallet/exchanges",
    name: "Exchanges",
    component: () => import("@/views/wallet/ExchangeListView.vue"),
    meta: { title: "闪兑记录" }
  },
  {
    path: "wallet/treasury",
    name: "Treasury",
    component: () => import("@/views/wallet/TreasuryView.vue"),
    meta: { title: "链上钱包" }
  },
  {
    path: "wallet/sweep-logs",
    name: "SweepLogs",
    component: () => import("@/views/wallet/SweepLogsView.vue"),
    meta: { title: "归集记录" }
  },
  {
    path: "wallet/life-payments/orders",
    name: "LifePaymentOrders",
    component: () => import("@/views/wallet/LifePaymentOrdersView.vue"),
    meta: { title: "缴费订单" }
  },
  {
    path: "wallet/life-payments/tasks",
    name: "LifePaymentTasks",
    component: () => import("@/views/wallet/LifePaymentTasksView.vue"),
    meta: { title: "缴费任务" }
  },
  {
    path: "wallet/life-payments/workers",
    name: "LifePaymentWorkers",
    component: () => import("@/views/wallet/LifePaymentWorkersView.vue"),
    meta: { title: "缴费工人" }
  },
  {
    path: "wallet/life-payments/providers",
    name: "LifePaymentProviders",
    component: () => import("@/views/wallet/LifePaymentProvidersView.vue"),
    meta: { title: "缴费供应商" }
  },
  {
    path: "wallet/currencies",
    name: "Currencies",
    component: () => import("@/views/wallet/CurrencyListView.vue"),
    meta: { title: "币种管理" }
  },
  {
    path: "wallet/exchange-config",
    name: "ExchangeConfig",
    component: () => import("@/views/wallet/ExchangeConfigView.vue"),
    meta: { title: "闪兑配置" }
  },
  {
    path: "wallet/fee-config",
    name: "FeeConfig",
    component: () => import("@/views/wallet/FeeConfigView.vue"),
    meta: { title: "手续费配置" }
  },
  {
    path: "wallet/limit-config",
    name: "LimitConfig",
    component: () => import("@/views/wallet/LimitConfigView.vue"),
    meta: { title: "限额配置" }
  },
  {
    path: "ops/announcements/send",
    name: "AnnouncementSend",
    component: () => import("@/views/operations/AnnouncementSendView.vue"),
    meta: { title: "发送公告" }
  },
  {
    path: "ops/announcements",
    name: "AnnouncementList",
    component: () => import("@/views/operations/AnnouncementListView.vue"),
    meta: { title: "公告记录" }
  },
  {
    path: "ops/official-accounts",
    name: "OfficialAccounts",
    component: () => import("@/views/operations/OfficialAccountsView.vue"),
    meta: { title: "公众号" }
  },
  {
    path: "ops/feedback",
    name: "Feedback",
    component: () => import("@/views/operations/FeedbackListView.vue"),
    meta: { title: "用户反馈" }
  },
  {
    path: "ops/client-versions",
    name: "ClientVersions",
    component: () => import("@/views/operations/ClientVersionView.vue"),
    meta: { title: "版本发布" }
  },
  {
    path: "ops/splash",
    name: "Splash",
    component: () => import("@/views/operations/SplashView.vue"),
    meta: { title: "启动图" }
  },
  {
    path: "ops/wallet-notice",
    name: "WalletNotice",
    component: () => import("@/views/operations/WalletNoticeView.vue"),
    meta: { title: "支付助手通知" }
  },
  {
    path: "ops/friend-backfill",
    name: "FriendBackfill",
    component: () => import("@/views/operations/FriendBackfillView.vue"),
    meta: { title: "系统好友补全" }
  },
  {
    path: "ops/api-monitor",
    name: "ApiMonitor",
    component: () => import("@/views/operations/ApiMonitorView.vue"),
    meta: { title: "接口监控" }
  },
  {
    path: "system/config",
    name: "PlatformConfig",
    component: () => import("@/views/system/PlatformConfigView.vue"),
    meta: { title: "平台配置" }
  },
  {
    path: "system/audit-logs",
    name: "AuditLogs",
    component: () => import("@/views/system/AuditLogsView.vue"),
    meta: { title: "操作审计" }
  },
  {
    path: "system/admin-login-logs",
    name: "AdminLoginLogs",
    component: () => import("@/views/system/AdminLoginLogsView.vue"),
    meta: { title: "管理员登录" }
  },
  {
    path: "system/profile",
    name: "Profile",
    component: () => import("@/views/system/ProfileView.vue"),
    meta: { title: "个人设置" }
  },
  {
    path: "system/advanced",
    name: "AdvancedOps",
    component: () => import("@/views/system/AdvancedOpsView.vue"),
    meta: { title: "高级运维" }
  }
];

export const routes: RouteRecordRaw[] = [
  {
    path: "/login",
    name: "Login",
    component: () => import("@/views/login/LoginView.vue"),
    meta: { title: "登录" }
  },
  {
    path: "/",
    component: AdminLayout,
    redirect: "/dashboard",
    children: layoutChildren
  },
  { path: "/:pathMatch(.*)*", redirect: "/dashboard" }
];
