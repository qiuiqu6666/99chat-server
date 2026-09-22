import { request, unwrap } from "./http";

export type DashboardStats = {
  user_total?: number;
  registered_today?: number;
  login_today?: number;
  active_today?: number;
  online_users?: number;
  group_total?: number;
  group_created_today?: number;
  message_today?: number;
  c2c_message_today?: number;
  group_message_today?: number;
  recharge_amount_today?: string;
  withdraw_amount_today?: string;
  pending_withdraw_count?: number;
  red_packet_amount_today?: string;
  transfer_amount_today?: string;
  pending_complaint_count?: number;
  pending_feedback_count?: number;
  life_payment_abnormal_count?: number;
};

export async function getDashboardOverview() {
  const raw = await request<Record<string, unknown>>("get", "/dashboard/overview");
  const data = unwrap(raw);
  return {
    stats: (data.stats || data) as DashboardStats,
    site_wallet_funds: (data.site_wallet_funds || data.siteWalletFunds || []) as Record<string, unknown>[]
  };
}
