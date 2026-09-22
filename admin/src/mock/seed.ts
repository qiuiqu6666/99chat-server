import { isoRecent, unixMsRecent } from "./util";

export type MockUser = {
  user_uid: number;
  user_mail: string | null;
  nickname: string | null;
  user_sex: number;
  phone_num: string | null;
  register_ip: string | null;
  register_time: string | null;
  latest_login_time: string | null;
  latest_login_ip: string | null;
  user_status: number;
  is_online: number;
  user_avatar_file_name: string | null;
  what_s_up: string | null;
  user_desc: string | null;
  user_type: number;
  user_regieon: string | null;
  device_type: number | null;
  termination_time: string | null;
  nickname_last_modified_time: string | null;
  nickname_last_modified_time2: number;
  wallet_balance: string | null;
  wallet_frozen_amount: string | null;
  friend_count: number;
  group_count: number;
  hardware_id?: string;
};

const surnames = ["张", "李", "王", "刘", "陈", "杨", "赵", "黄", "周", "吴"];
const regions = ["广东", "浙江", "江苏", "四川", "北京", "上海", "福建"];

function phone(i: number) {
  return `1${3 + (i % 7)}${String(100000000 + i * 7919).slice(1, 10)}`;
}

function buildUsers(count: number): MockUser[] {
  const list: MockUser[] = [];
  for (let i = 0; i < count; i++) {
    const uid = 400069 + i;
    const online = Math.random() > 0.45 ? 1 : 0;
    const bal = (Math.random() * 8000 + 10).toFixed(2);
    const frozen = (Math.random() < 0.15 ? Math.random() * 200 : 0).toFixed(2);
    list.push({
      user_uid: uid,
      user_mail: i % 3 === 0 ? `user${uid}@example.com` : null,
      nickname: surnames[i % surnames.length] + String.fromCharCode(65 + (i % 26)),
      user_sex: i % 3,
      phone_num: phone(i),
      register_ip: `103.${(i % 200) + 10}.${(i % 250) + 1}.${(i % 200) + 10}`,
      register_time: isoRecent(700),
      latest_login_time: isoRecent(30),
      latest_login_ip: `36.${(i % 180) + 20}.${(i % 250) + 1}.${(i % 200) + 10}`,
      user_status: i % 11 === 0 ? 0 : 1,
      is_online: online,
      user_avatar_file_name: i % 4 === 0 ? `avatar_${uid}.jpg` : null,
      what_s_up: i % 2 === 0 ? "Hello IM" : null,
      user_desc: null,
      user_type: 0,
      user_regieon: regions[i % regions.length],
      device_type: i % 4 === 0 ? 0 : i % 4 === 1 ? 1 : 2,
      termination_time: null,
      nickname_last_modified_time: isoRecent(120),
      nickname_last_modified_time2: unixMsRecent(120),
      wallet_balance: bal,
      wallet_frozen_amount: frozen,
      friend_count: Math.floor(Math.random() * 80) + 1,
      group_count: Math.floor(Math.random() * 20),
      hardware_id: `fp_mock_${uid.toString(16)}`
    });
  }
  // 同 IP 簇
  [0, 1, 2].forEach(gi => {
    const ip = `103.236.254.${90 + gi}`;
    for (let j = 0; j < 3; j++) {
      const u = list[gi * 8 + j + 1];
      if (u) u.latest_login_ip = ip;
    }
  });
  // 同设备簇
  const dev = "fp_sm_demo_7a9012bcef";
  for (let j = 0; j < 4; j++) {
    const u = list[20 + j];
    if (u) u.hardware_id = dev;
  }
  return list;
}

export type MockGroup = Record<string, unknown>;

function buildGroups(n: number, users: MockUser[]): MockGroup[] {
  const rows: MockGroup[] = [];
  for (let i = 1; i <= n; i++) {
    const owner = users[i % users.length];
    rows.push({
      g_id: `G${String(100000 + i)}`,
      g_name: `演示群${i} · ${owner.nickname ?? "群"}`,
      g_owner_user_uid: owner.user_uid,
      create_user_uid: owner.user_uid,
      create_user_nickname: owner.nickname,
      g_member_count: 20 + (i % 180),
      max_member_count: 500,
      g_status: i % 9 === 0 ? 2 : i % 13 === 0 ? 1 : 0,
      invite_mode: i % 2 === 0 ? "自由加入" : "邀请审核",
      create_time: unixMsRecent(600)
    });
  }
  return rows;
}

function buildLoginLogs(users: MockUser[]) {
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < 120; i++) {
    const u = users[i % users.length];
    rows.push({
      history_id: 90000 + i,
      user_uid: u.user_uid,
      user_nickname: u.nickname,
      device_type: u.device_type,
      login_time: isoRecent(14),
      login_time2: unixMsRecent(14),
      login_ip: u.latest_login_ip,
      device_info: "IM-App/2.1.0 (Android 14)",
      hardware_id: u.hardware_id ?? "",
      device_token_masked: "****" + String(i).slice(-4),
      status: "success",
      is_current: i % 17 === 0 ? 1 : 0
    });
  }
  return rows.sort(
    (a, b) =>
      Number(b.login_time2 ?? 0) - Number(a.login_time2 ?? 0)
  );
}

function buildRedPackets(users: MockUser[]) {
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < 80; i++) {
    const u = users[i % users.length];
    rows.push({
      id: `RP${100000 + i}`,
      sender_uid: u.user_uid,
      nickname: u.nickname,
      packet_type: i % 3 === 0 ? "群红包" : "私聊红包",
      group_id: i % 3 === 0 ? `G${100010 + (i % 20)}` : null,
      total_amount: (Math.random() * 500 + 1).toFixed(2),
      total_count: 5 + (i % 10),
      receive_count: i % 5,
      status: String(i % 3),
      create_time: unixMsRecent(30)
    });
  }
  return rows.sort(
    (a, b) => Number(b.create_time) - Number(a.create_time)
  );
}

function buildTransfers(users: MockUser[]) {
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < 90; i++) {
    const from = users[i % users.length];
    const to = users[(i + 3) % users.length];
    rows.push({
      transaction_no: `TX${Date.now().toString().slice(-8)}${i}`,
      from_uid: from.user_uid,
      from_nickname: from.nickname,
      to_uid: to.user_uid,
      to_nickname: to.nickname,
      amount: (Math.random() * 2000 + 1).toFixed(2),
      fee: "0.00",
      status: i % 7 === 0 ? "2" : "1",
      remark: i % 4 === 0 ? "转账备注" : "",
      create_time: unixMsRecent(45)
    });
  }
  return rows.sort(
    (a, b) => Number(b.create_time) - Number(a.create_time)
  );
}

function buildWalletLedger(userUid: number) {
  const types = [
    [1, "充值"],
    [2, "提现"],
    [3, "转账支出"],
    [4, "转账收入"],
    [5, "红包发出"],
    [6, "红包领取"],
    [7, "余额调整"]
  ];
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < 35; i++) {
    const [t, label] = types[i % types.length];
    rows.push({
      transaction_no: `WL${userUid}_${i}`,
      user_uid: userUid,
      transaction_type: t,
      transaction_type_label: label,
      amount: (Math.random() * 300 + 1).toFixed(2),
      status: "1",
      remark: `${label} · 演示`,
      create_time: unixMsRecent(60)
    });
  }
  return rows.sort(
    (a, b) => Number(b.create_time) - Number(a.create_time)
  );
}

function buildRechargeWithdraw(users: MockUser[]) {
  const rows: Record<string, unknown>[] = [];
  const channels = ["微信", "支付宝", "银联"];
  for (let i = 0; i < 60; i++) {
    const u = users[i % users.length];
    const biz = i % 2 === 0 ? "充值" : "提现";
    rows.push({
      biz_no: `BW${100000 + i}`,
      user_uid: u.user_uid,
      nickname: u.nickname,
      biz_type: biz,
      channel: channels[i % 3],
      amount: (Math.random() * 5000 + 10).toFixed(2),
      status: i % 5 === 0 ? "处理中" : "成功",
      external_order_no: `EXT${i}${Date.now().toString().slice(-6)}`,
      create_time: unixMsRecent(50)
    });
  }
  return rows.sort(
    (a, b) => Number(b.create_time) - Number(a.create_time)
  );
}

function buildAdminLoginLogs() {
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < 40; i++) {
    const ok = i % 6 !== 0;
    rows.push({
      id: i + 1,
      admin_user_id: 1,
      username_attempted: ok ? "admin" : "guest",
      success: ok ? 1 : 0,
      ip: `114.${100 + (i % 50)}.${i % 200}.${i % 200}`,
      geo_address: "中国",
      user_agent: "Mozilla/5.0 Chrome/120",
      fail_reason: ok ? null : "invalid_credentials",
      login_at: unixMsRecent(20)
    });
  }
  return rows.sort(
    (a, b) => Number(b.login_at) - Number(a.login_at)
  );
}

function buildAdminAuditLogs() {
  const actions = [
    "user.login_password",
    "user.wallet.balance_adjust",
    "group.read",
    "announcement.send"
  ];
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < 50; i++) {
    rows.push({
      id: i + 1,
      admin_user_id: 1,
      admin_username: "admin",
      action: actions[i % actions.length],
      resource_type: "user",
      resource_id: String(400069 + (i % 20)),
      detail: "演示审计记录",
      created_at: unixMsRecent(25)
    });
  }
  return rows.sort(
    (a, b) => Number(b.created_at) - Number(a.created_at)
  );
}

function buildMessagesC2c(a: number, b: number) {
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < 25; i++) {
    const from = i % 2 === 0 ? a : b;
    rows.push({
      collect_id: 100000 + i,
      src_uid: from,
      dest_uid: from === a ? b : a,
      msg_type: 0,
      msg_content: `演示单聊消息 #${i + 1}`,
      msg_time2: unixMsRecent(7)
    });
  }
  return rows.sort(
    (x, y) => Number(y.msg_time2) - Number(x.msg_time2)
  );
}

function buildMessagesGroup(gId: string) {
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < 30; i++) {
    rows.push({
      collect_id: 200000 + i,
      src_uid: 400069 + (i % 10),
      group_id: gId,
      g_id: gId,
      msg_type: 0,
      msg_content: `群消息演示 #${i + 1}`,
      msg_time2: unixMsRecent(10)
    });
  }
  return rows.sort(
    (x, y) => Number(y.msg_time2) - Number(x.msg_time2)
  );
}

function buildGroupMembers(gId: string, users: MockUser[]) {
  return users.slice(0, 15).map((u, i) => ({
    user_uid: u.user_uid,
    nickname: u.nickname,
    role: i === 0 ? "owner" : i < 3 ? "admin" : "member",
    join_time: unixMsRecent(200),
    muted: i % 11 === 0 ? 1 : 0
  }));
}

function buildGroupOpLogs(gId?: string) {
  const actions = ["member_join", "member_leave", "mute_all", "rename"];
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < 40; i++) {
    rows.push({
      id: i + 1,
      g_id: gId ?? `G${100010 + (i % 20)}`,
      kind: actions[i % actions.length],
      actor_uid: 400069 + (i % 8),
      detail: "演示群事件",
      created_at: unixMsRecent(30)
    });
  }
  return rows.sort(
    (a, b) => Number(b.created_at) - Number(a.created_at)
  );
}

export function createMockSeed() {
  const users = buildUsers(86);
  const groups = buildGroups(48, users);
  return {
    users,
    groups,
    loginLogs: buildLoginLogs(users),
    redPackets: buildRedPackets(users),
    transfers: buildTransfers(users),
    rechargeWithdraw: buildRechargeWithdraw(users),
    adminLoginLogs: buildAdminLoginLogs(),
    adminAuditLogs: buildAdminAuditLogs(),
    buildWalletLedger,
    buildMessagesC2c,
    buildMessagesGroup,
    buildGroupMembers,
    buildGroupOpLogs
  };
}

export type MockSeed = ReturnType<typeof createMockSeed>;
