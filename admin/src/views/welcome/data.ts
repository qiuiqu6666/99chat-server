import { dayjs, cloneDeep, getRandomIntBetween } from "./utils";
import UserThree from "~icons/ri/user-3-line";
import UserAddLine from "~icons/ri/user-add-line";
import LoginCircleLine from "~icons/ri/login-circle-line";
import GroupLine from "~icons/ri/group-line";
import WifiLine from "~icons/ri/wifi-line";
import WalletLine from "~icons/ri/wallet-line";
import Lock2Line from "~icons/ri/lock-2-line";

const days = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

/**
 * 与 `DashboardOverview.stats` 对齐：前 7 项为常用 KPI；
 * `wallet_balance_total` / `wallet_frozen_total` 为字符串金额，卡片内用大字展示而非 CountTo。
 */
export const KPI_STAT_KEYS = [
  "user_total",
  "registered_today",
  "online_users",
  "login_today",
  "group_total",
  "wallet_balance_total",
  "wallet_frozen_total"
] as const;

export type KpiStatKey = (typeof KPI_STAT_KEYS)[number];

export const KPI_MONEY_KEYS: ReadonlySet<KpiStatKey> = new Set([
  "wallet_balance_total",
  "wallet_frozen_total"
]);

/** 顶层 KPI 卡片：仅前端视觉配置 */
export const kpiCardVisualTemplates = [
  {
    icon: UserThree,
    bgColor: "#effaff",
    color: "#41b6ff",
    duration: 2200
  },
  {
    icon: UserAddLine,
    bgColor: "#fff5f4",
    color: "#e85f33",
    duration: 1600
  },
  {
    icon: WifiLine,
    bgColor: "#f0f7ff",
    color: "#1d8dff",
    duration: 1500
  },
  {
    icon: LoginCircleLine,
    bgColor: "#eff8f4",
    color: "#26ce83",
    duration: 1500
  },
  {
    icon: GroupLine,
    bgColor: "#f6f4fe",
    color: "#7846e5",
    duration: 1800
  },
  {
    icon: WalletLine,
    bgColor: "#fff9f0",
    color: "#d48806",
    duration: 1800
  },
  {
    icon: Lock2Line,
    bgColor: "#f5fbfb",
    color: "#13a8a8",
    duration: 1600
  }
];

/** 接口未就绪或字段缺失时的标题回退（与后端 `labels` 默认语义一致即可） */
export const defaultKpiLabels: Record<KpiStatKey, string> = {
  user_total: "用户总数",
  registered_today: "今日注册",
  online_users: "当前在线用户",
  login_today: "今日登录用户",
  group_total: "群组数量",
  wallet_balance_total: "全站可用余额",
  wallet_frozen_total: "全站冻结金额"
};

/** 用户注册与在线用户每日统计（演示：按自然日聚合） */
const tableData = Array.from({ length: 30 }).map((_, index) => {
  const peakOnline = getRandomIntBetween(4200, 9200);
  const avgOnline = Math.min(
    peakOnline - 1,
    Math.round(peakOnline * (0.52 + Math.random() * 0.24))
  );
  return {
    id: index + 1,
    date: dayjs().subtract(index, "day").format("YYYY-MM-DD"),
    /** 当日新完成注册用户数 */
    newRegistrations: getRandomIntBetween(72, 356),
    /** 当日在线人数峰值（含多端去重可按后端口径替换） */
    peakOnline,
    /** 当日在线人数简单均值（演示） */
    avgOnline: Math.max(800, avgOnline)
  };
});

/** 最新动态 */
const latestNewsData = cloneDeep(tableData)
  .slice(0, 14)
  .map((item, index) => {
    return Object.assign(item, {
      date: `${dayjs().subtract(index, "day").format("YYYY-MM-DD")} ${
        days[dayjs().subtract(index, "day").day()]
      }`
    });
  });

export {
  tableData,
  latestNewsData
};
