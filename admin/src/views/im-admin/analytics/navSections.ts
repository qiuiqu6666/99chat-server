/** 工作台「功能导航」与侧栏菜单保持一致，便于一览全部入口 */
export type NavItem = { label: string; path: string; note?: string };

export type NavSection = { title: string; items: NavItem[] };

export const IM_ADMIN_NAV_SECTIONS: NavSection[] = [
  {
    title: "IM",
    items: [
      { label: "用户列表", path: "/im-admin/users/list" },
      { label: "好友关系", path: "/im-admin/relations/index" },
      { label: "群组列表", path: "/im-admin/groups/list" },
      { label: "群动态", path: "/im-admin/groups/logs" },
      { label: "消息审计", path: "/im-admin/messages/query" },
      { label: "设备终端", path: "/im-admin/devices/index" },
      { label: "登录记录", path: "/im-admin/devices/login-records" }
    ]
  },
  {
    title: "资金",
    items: [
      { label: "账变记录", path: "/im-admin/wallet/ledger" },
      { label: "充值记录", path: "/im-admin/recharges/list" },
      { label: "提现审核", path: "/im-admin/withdraws/list" },
      { label: "红包记录", path: "/im-admin/red-packets/list" },
      { label: "转账记录", path: "/im-admin/transfers/list" },
      { label: "闪兑记录", path: "/im-admin/transfers/exchanges" },
      { label: "链上钱包", path: "/im-admin/wallet/treasury" },
      { label: "归集记录", path: "/im-admin/wallet/sweep-logs" },
      { label: "币种管理", path: "/im-admin/wallet/currencies" },
      { label: "闪兑配置", path: "/im-admin/wallet/exchange-config" }
    ]
  },
  {
    title: "运营",
    items: [
      { label: "发送公告", path: "/im-admin/announcements/send" },
      { label: "公告记录", path: "/im-admin/announcements/list" },
      { label: "用户反馈", path: "/im-admin/announcements/feedback" },
      { label: "版本发布", path: "/im-admin/client-version/index" },
      { label: "启动图", path: "/im-admin/splash/index" }
    ]
  },
  {
    title: "系统",
    items: [
      { label: "平台配置", path: "/im-admin/system-config/index" },
      { label: "操作审计", path: "/im-admin/audit/logs" },
      { label: "管理员登录", path: "/im-admin/audit/admin-login" },
      { label: "个人设置", path: "/im-admin/profile/index" }
    ]
  }
];
