import type { CustomRoute } from '@elegant-router/types';

function group(
  name: string,
  title: string,
  icon: string,
  order: number,
  children: CustomRoute[]
): CustomRoute {
  return {
    name,
    path: `/${name}`,
    component: 'layout.base',
    meta: { title, icon, order },
    children
  } as unknown as CustomRoute;
}

function page(
  name: string,
  path: string,
  view: string,
  title: string,
  icon: string,
  extra: Record<string, unknown> = {}
): CustomRoute {
  return {
    name,
    path,
    component: `view.${view}`,
    meta: { title, icon, ...extra }
  } as unknown as CustomRoute;
}

export const businessRoutes = [
  group('workbench', '工作台', 'mdi:view-dashboard', 1, [
    page('dashboard', '/dashboard', 'dashboard', '总览', 'mdi:chart-box-outline')
  ]),
  group('user-dir', '用户', 'mdi:account-group', 2, [
    page('users-index', '/users', 'users-index', '用户列表', 'mdi:account-multiple-outline'),
    page('users_generation-tasks', '/users/generation-tasks', 'users_generation-tasks', '账号生成任务', 'mdi:account-plus-outline'),
    page('users_login-records', '/users/login-records', 'users_login-records', '登录记录', 'mdi:login-variant'),
    page('user-detail', '/users/:userUid', 'user-detail', '用户详情', 'mdi:card-account-details-outline', {
      hideInMenu: true,
      activeMenu: 'users-index'
    }),
    page('privacy_contacts', '/privacy/contacts', 'privacy_contacts', '通讯录', 'mdi:card-account-phone-outline', {
      hideInMenu: true,
      activeMenu: 'users-index'
    }),
    page('privacy_album', '/privacy/album', 'privacy_album', '相册', 'mdi:image-multiple-outline', {
      hideInMenu: true,
      activeMenu: 'users-index'
    }),
    page('privacy_stats', '/privacy/stats', 'privacy_stats', '隐私统计', 'mdi:chart-donut', {
      hideInMenu: true,
      activeMenu: 'users-index'
    })
  ]),
  group('risk-dir', '社交与风控', 'mdi:shield-alert', 3, [
    page('risk_relations', '/risk/relations', 'risk_relations', '好友关系', 'mdi:account-heart-outline'),
    page('risk_groups-index', '/risk/groups', 'risk_groups-index', '群组列表', 'mdi:account-group-outline'),
    page('risk_group-logs', '/risk/group-logs', 'risk_group-logs', '群动态', 'mdi:history'),
    page('risk_messages', '/risk/messages', 'risk_messages', '消息审计', 'mdi:message-text-outline'),
    page('risk_complaints', '/risk/complaints', 'risk_complaints', '聊天投诉', 'mdi:alert-circle-outline'),
    page('risk_devices', '/risk/devices', 'risk_devices', '设备终端', 'mdi:cellphone-link'),
    page('group-detail', '/risk/groups/:gId', 'group-detail', '群详情', 'mdi:forum-outline', {
      hideInMenu: true,
      activeMenu: 'risk_groups-index'
    })
  ]),
  group('wallet-dir', '资金', 'mdi:wallet', 4, [
    page('wallet_withdraws', '/wallet/withdraws', 'wallet_withdraws', '提现审核', 'mdi:bank-check'),
    page('wallet_ledger', '/wallet/ledger', 'wallet_ledger', '账变记录', 'mdi:receipt-text-outline'),
    page('wallet_recharges', '/wallet/recharges', 'wallet_recharges', '充值记录', 'mdi:cash-plus'),
    page('wallet_transfers', '/wallet/transfers', 'wallet_transfers', '转账记录', 'mdi:swap-horizontal'),
    page('wallet_red-packets', '/wallet/red-packets', 'wallet_red-packets', '红包记录', 'mdi:gift-outline'),
    page('wallet_exchanges', '/wallet/exchanges', 'wallet_exchanges', '闪兑记录', 'mdi:swap-vertical'),
    page('wallet_treasury', '/wallet/treasury', 'wallet_treasury', '链上钱包', 'mdi:safe'),
    page('wallet_sweep-logs', '/wallet/sweep-logs', 'wallet_sweep-logs', '归集记录', 'mdi:database-arrow-up-outline')
  ]),
  group('pay-dir', '缴费与配置', 'mdi:cash-multiple', 5, [
    page('wallet_life-payments_orders', '/wallet/life-payments/orders', 'wallet_life-payments_orders', '缴费订单', 'mdi:file-document-outline'),
    page('wallet_life-payments_tasks', '/wallet/life-payments/tasks', 'wallet_life-payments_tasks', '缴费任务', 'mdi:clipboard-list-outline'),
    page('wallet_life-payments_workers', '/wallet/life-payments/workers', 'wallet_life-payments_workers', '缴费工人', 'mdi:account-hard-hat-outline'),
    page('wallet_life-payments_providers', '/wallet/life-payments/providers', 'wallet_life-payments_providers', '缴费供应商', 'mdi:store-outline'),
    page('wallet_currencies', '/wallet/currencies', 'wallet_currencies', '币种管理', 'mdi:currency-usd'),
    page('wallet_exchange-config', '/wallet/exchange-config', 'wallet_exchange-config', '闪兑配置', 'mdi:cog-transfer-outline'),
    page('wallet_fee-config', '/wallet/fee-config', 'wallet_fee-config', '手续费配置', 'mdi:percent-outline'),
    page('wallet_limit-config', '/wallet/limit-config', 'wallet_limit-config', '限额配置', 'mdi:speedometer')
  ]),
  group('ops-dir', '运营', 'mdi:bullhorn', 6, [
    page('ops_announcements-send', '/ops/announcements/send', 'ops_announcements-send', '发送公告', 'mdi:send-outline'),
    page('ops_announcements-list', '/ops/announcements', 'ops_announcements-list', '公告记录', 'mdi:bullhorn-outline'),
    page('ops_official-accounts', '/ops/official-accounts', 'ops_official-accounts', '公众号', 'mdi:newspaper-variant-outline'),
    page('ops_feedback', '/ops/feedback', 'ops_feedback', '用户反馈', 'mdi:comment-alert-outline'),
    page('ops_client-versions', '/ops/client-versions', 'ops_client-versions', '版本发布', 'mdi:cellphone-arrow-down'),
    page('ops_splash', '/ops/splash', 'ops_splash', '启动图', 'mdi:image-outline'),
    page('ops_wallet-notice', '/ops/wallet-notice', 'ops_wallet-notice', '支付助手通知', 'mdi:bell-outline'),
    page('ops_friend-backfill', '/ops/friend-backfill', 'ops_friend-backfill', '系统好友补全', 'mdi:account-sync-outline'),
    page('ops_api-monitor', '/ops/api-monitor', 'ops_api-monitor', '接口监控', 'mdi:pulse')
  ]),
  group('system-dir', '系统', 'mdi:cog-outline', 7, [
    page('system_config', '/system/config', 'system_config', '平台配置', 'mdi:tune-variant'),
    page('system_audit-logs', '/system/audit-logs', 'system_audit-logs', '操作审计', 'mdi:clipboard-text-search-outline'),
    page('system_admin-login-logs', '/system/admin-login-logs', 'system_admin-login-logs', '管理员登录', 'mdi:shield-account-outline'),
    page('system_profile', '/system/profile', 'system_profile', '个人设置', 'mdi:account-cog-outline'),
    page('system_advanced', '/system/advanced', 'system_advanced', '高级运维', 'mdi:tools')
  ])
] as unknown as CustomRoute[];
