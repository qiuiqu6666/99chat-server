import { http } from "@/utils/http";
import { unwrapApiData } from "@/utils/chat99AdminApi";

/** P0 首页基础看板：去掉隐私访问统计，只保留运营、消息、资金、系统摘要。 */
export type DashboardStats = {
  user_total: number;
  registered_today: number;
  login_today: number;
  active_today?: number;
  online_users?: number;
  group_total: number;
  group_created_today?: number;
  message_today?: number;
  c2c_message_today?: number;
  group_message_today?: number;
  recharge_amount_today?: string;
  withdraw_amount_today?: string;
  pending_withdraw_count?: number;
  red_packet_amount_today?: string;
  transfer_amount_today?: string;
  system_error_count?: number;
  wallet_balance_total?: string;
  wallet_frozen_total?: string;
};

export type SiteWalletFundItem = {
  currency: string;
  label: string;
  available: string;
  frozen: string;
  total: string;
};

export type DashboardOverview = {
  stats: DashboardStats;
  site_wallet_funds?: SiteWalletFundItem[];
  labels?: Partial<Record<keyof DashboardStats, string>>;
};

/** 多日注册与在线：`GET /api/v1/dashboard/daily-metrics`（后端可选） */
export type DashboardDailyMetricRow = {
  date?: string;
  stat_date?: string;
  registered_count?: number;
  new_registrations?: number;
  login_distinct_users?: number;
  groups_created_count?: number;
  peak_online_users?: number;
  online_users_peak?: number;
  avg_online_users?: number;
  login_users?: number;
  login_users_count?: number;
};

export type DashboardDailyMetricsParams = {
  days?: number;
  page?: number;
  page_size?: number;
};

export type DashboardDailyMetricsResponse = {
  items?: DashboardDailyMetricRow[];
  rows?: DashboardDailyMetricRow[];
  total?: number;
};

export const getDashboardDailyMetrics = (
  params: DashboardDailyMetricsParams = {}
) =>
  http.request<DashboardDailyMetricsResponse>(
    "get",
    "/api/v1/dashboard/daily-metrics",
    { params }
  );

export const getDashboardOverview = async () => {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/dashboard/overview"
  );
  const data = unwrapApiData(raw);
  const stats = (data.stats ?? data) as DashboardStats;
  const siteWalletFunds = (data.site_wallet_funds ?? data.siteWalletFunds) as
    | SiteWalletFundItem[]
    | undefined;
  return { stats, site_wallet_funds: siteWalletFunds, labels: data.labels ?? {} } as DashboardOverview;
};
