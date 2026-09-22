export type MenuItem = {
  title: string;
  path?: string;
  icon?: string;
  children?: MenuItem[];
};

/** 侧栏树：与 routes 二级一一对应 */
export const sideMenu: MenuItem[] = [
  { title: "工作台", icon: "Odometer", children: [{ title: "总览", path: "/dashboard" }] },
  {
    title: "用户",
    icon: "User",
    children: [
      { title: "用户列表", path: "/users" },
      { title: "账号生成任务", path: "/users/generation-tasks" },
      { title: "登录记录", path: "/users/login-records" }
    ]
  },
  {
    title: "社交与风控",
    icon: "Warning",
    children: [
      { title: "好友关系", path: "/risk/relations" },
      { title: "群组列表", path: "/risk/groups" },
      { title: "群动态", path: "/risk/group-logs" },
      { title: "消息审计", path: "/risk/messages" },
      { title: "聊天投诉", path: "/risk/complaints" },
      { title: "设备终端", path: "/risk/devices" }
    ]
  },
  {
    title: "资金",
    icon: "Wallet",
    children: [
      { title: "提现审核", path: "/wallet/withdraws" },
      { title: "账变记录", path: "/wallet/ledger" },
      { title: "充值记录", path: "/wallet/recharges" },
      { title: "转账记录", path: "/wallet/transfers" },
      { title: "红包记录", path: "/wallet/red-packets" },
      { title: "闪兑记录", path: "/wallet/exchanges" },
      { title: "链上钱包", path: "/wallet/treasury" },
      { title: "归集记录", path: "/wallet/sweep-logs" }
    ]
  },
  {
    title: "缴费与配置",
    icon: "Coin",
    children: [
      { title: "缴费订单", path: "/wallet/life-payments/orders" },
      { title: "缴费任务", path: "/wallet/life-payments/tasks" },
      { title: "缴费工人", path: "/wallet/life-payments/workers" },
      { title: "缴费供应商", path: "/wallet/life-payments/providers" },
      { title: "币种管理", path: "/wallet/currencies" },
      { title: "闪兑配置", path: "/wallet/exchange-config" },
      { title: "手续费配置", path: "/wallet/fee-config" },
      { title: "限额配置", path: "/wallet/limit-config" }
    ]
  },
  {
    title: "运营",
    icon: "Promotion",
    children: [
      { title: "发送公告", path: "/ops/announcements/send" },
      { title: "公告记录", path: "/ops/announcements" },
      { title: "公众号", path: "/ops/official-accounts" },
      { title: "用户反馈", path: "/ops/feedback" },
      { title: "版本发布", path: "/ops/client-versions" },
      { title: "启动图", path: "/ops/splash" },
      { title: "支付助手通知", path: "/ops/wallet-notice" },
      { title: "系统好友补全", path: "/ops/friend-backfill" },
      { title: "接口监控", path: "/ops/api-monitor" }
    ]
  },
  {
    title: "系统",
    icon: "Setting",
    children: [
      { title: "平台配置", path: "/system/config" },
      { title: "操作审计", path: "/system/audit-logs" },
      { title: "管理员登录", path: "/system/admin-login-logs" },
      { title: "个人设置", path: "/system/profile" },
      { title: "高级运维", path: "/system/advanced" }
    ]
  }
];
