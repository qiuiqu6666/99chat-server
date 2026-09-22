import { createMockSeed, type MockUser } from "./seed";
import { paginate } from "./util";

const seed = createMockSeed();

function findUser(uid: number): MockUser | undefined {
  return seed.users.find(u => u.user_uid === uid);
}

function filterUsers(params: {
  keyword?: string;
  status?: string;
  is_online?: string;
}) {
  let list = [...seed.users];
  const kw = params.keyword?.trim();
  if (kw) {
    const k = kw.toLowerCase();
    list = list.filter(
      u =>
        String(u.user_uid).includes(k) ||
        (u.nickname ?? "").includes(kw) ||
        (u.phone_num ?? "").includes(kw) ||
        (u.user_mail ?? "").toLowerCase().includes(k)
    );
  }
  if (params.status === "0") list = list.filter(u => u.user_status === 0);
  else if (params.status === "1") list = list.filter(u => u.user_status === 1);
  if (params.is_online === "1") list = list.filter(u => u.is_online === 1);
  else if (params.is_online === "0") list = list.filter(u => u.is_online === 0);
  return list;
}

export const mockApi = {
  auth: {
    login(data: { username: string; password: string }) {
      const username = String(data.username ?? "").trim();
      const password = String(data.password ?? "").trim();
      if (username === "admin" && password === "admin123") {
        return {
          access_token: `mock.jwt.${username}.${Date.now()}`,
          token_type: "Bearer",
          expires_in: 86400,
          user: {
            id: 1,
            username: "admin",
            display_name: "演示管理员",
            role: "super_admin",
            permissions: [
              "admin.manage",
              "dashboard.view",
              "group.read",
              "group.write",
              "system.config",
              "user.read",
              "user.write",
              "wallet.read"
            ]
          }
        };
      }
      const err = new Error("invalid_credentials") as Error & {
        response?: { status: number; data: { error: string } };
      };
      err.response = { status: 401, data: { error: "invalid_credentials" } };
      throw err;
    },
    me() {
      return {
        user: {
          id: 1,
          username: "admin",
          role: "super_admin",
          permissions: [
            "admin.manage",
            "dashboard.view",
            "group.read",
            "group.write",
            "user.read",
            "user.write",
            "wallet.read"
          ]
        }
      };
    },
    refreshToken() {
      const ms = Date.now() + 86400000;
      return {
        code: 0,
        message: "ok",
        data: {
          accessToken: `mock.refresh.${Date.now()}`,
          refreshToken: `mock.refresh.${Date.now()}`,
          expires: new Date(ms)
        }
      };
    }
  },

  dashboard: {
    overview() {
      const users = seed.users;
      const online = users.filter(u => u.is_online === 1).length;
      const bal = users.reduce((s, u) => s + Number(u.wallet_balance ?? 0), 0);
      const frozen = users.reduce(
        (s, u) => s + Number(u.wallet_frozen_amount ?? 0),
        0
      );
      return {
        stats: {
          user_total: users.length,
          registered_today: 3,
          login_today: 12,
          group_total: seed.groups.length,
          wallet_balance_total: bal.toFixed(2),
          wallet_frozen_total: frozen.toFixed(2),
          online_users: online
        }
      };
    },
    dailyMetrics(params: { days?: number }) {
      const days = Math.min(params.days ?? 7, 30);
      const items = Array.from({ length: days }, (_, i) => {
        const d = new Date();
        d.setDate(d.getDate() - (days - 1 - i));
        return {
          date: d.toISOString().slice(0, 10),
          registered_count: Math.floor(Math.random() * 8) + 1,
          login_distinct_users: Math.floor(Math.random() * 20) + 5,
          avg_online_users: Math.floor(Math.random() * 15) + 3
        };
      });
      return { items, total: items.length };
    }
  },

  users: {
    list(params: {
      page: number;
      page_size: number;
      keyword?: string;
      status?: string;
      is_online?: string;
      sort?: string;
    }) {
      const list = filterUsers(params);
      const p = paginate(list, params.page, params.page_size);
      return { ...p, sort: params.sort ?? "user_uid_desc" };
    },
    detail(user_uid: string | number) {
      const uid = Number(user_uid);
      const profile = findUser(uid);
      if (!profile) {
        const err = new Error("user_not_found") as Error & {
          response?: { status: number; data: { error: string } };
        };
        err.response = { status: 404, data: { error: "user_not_found" } };
        throw err;
      }
      const related = seed.users
        .filter(
          u =>
            u.user_uid !== uid &&
            u.latest_login_ip === profile.latest_login_ip
        )
        .slice(0, 5);
      return {
        profile,
        groups: {
          total: profile.group_count,
          limit: 10,
          truncated: profile.group_count > 10,
          items: seed.groups.slice(0, 3).map(g => ({
            g_id: g.g_id,
            g_name: g.g_name
          }))
        },
        friends: {
          total: profile.friend_count,
          limit: 10,
          truncated: profile.friend_count > 10,
          items: seed.users
            .filter(u => u.user_uid !== uid)
            .slice(0, 5)
            .map(u => ({
              user_uid: u.user_uid,
              nickname: u.nickname
            }))
        },
        accounts_same_ip: {
          total: related.length,
          limit: 10,
          truncated: false,
          shared_ips: profile.latest_login_ip ? [profile.latest_login_ip] : [],
          items: related
        },
        devices: {
          total: 2,
          limit: 10,
          truncated: false,
          items: [
            {
              device_type: profile.device_type,
              login_ip: profile.latest_login_ip,
              hardware_id: profile.hardware_id,
              login_time: profile.latest_login_time
            }
          ]
        }
      };
    },
    relatedByIp(params: { user_uid: string | number; page?: number; page_size?: number }) {
      const profile = findUser(Number(params.user_uid));
      if (!profile) return { items: [], total: 0, hint: "user_not_found" };
      const items = seed.users.filter(
        u =>
          u.user_uid !== profile.user_uid &&
          u.latest_login_ip === profile.latest_login_ip
      );
      const p = paginate(items, params.page ?? 1, params.page_size ?? 50);
      return {
        ...p,
        shared_values: profile.latest_login_ip ? [profile.latest_login_ip] : [],
        hint: items.length ? undefined : "no_login_ip_in_history"
      };
    },
    relatedByDevice(params: { user_uid: string | number; page?: number; page_size?: number }) {
      const profile = findUser(Number(params.user_uid));
      if (!profile) return { items: [], total: 0, hint: "user_not_found" };
      const items = seed.users.filter(
        u =>
          u.user_uid !== profile.user_uid &&
          u.hardware_id === profile.hardware_id
      );
      const p = paginate(items, params.page ?? 1, params.page_size ?? 50);
      return {
        ...p,
        shared_values: profile.hardware_id ? [profile.hardware_id] : [],
        hint: items.length ? undefined : "no_device_in_history"
      };
    },
    create(data: { nickname: string; password: string; sex?: string }) {
      const uid = 500000 + seed.users.length + 1;
      const row: MockUser = {
        user_uid: uid,
        user_mail: null,
        nickname: data.nickname,
        user_sex: Number(data.sex ?? 2),
        phone_num: null,
        register_ip: "127.0.0.1",
        register_time: new Date().toISOString().slice(0, 19).replace("T", " "),
        latest_login_time: null,
        latest_login_ip: null,
        user_status: 1,
        is_online: 0,
        user_avatar_file_name: null,
        what_s_up: null,
        user_desc: null,
        user_type: 0,
        user_regieon: null,
        device_type: null,
        termination_time: null,
        nickname_last_modified_time: null,
        nickname_last_modified_time2: 0,
        wallet_balance: "0.00",
        wallet_frozen_amount: "0.00",
        friend_count: 0,
        group_count: 0
      };
      seed.users.unshift(row);
      return {
        ok: true,
        user_uid: String(uid),
        nickname: data.nickname,
        trx_address: null
      };
    },
    createBatch(data: { users: Array<{ nickname: string; password: string; sex?: string }> }) {
      const items = data.users.map((u, index) => {
        try {
          const res = mockApi.users.create(u);
          return { index, ok: true, ...res };
        } catch (e) {
          return {
            index,
            ok: false,
            error: "create_failed",
            message: String(e)
          };
        }
      });
      const success_count = items.filter(i => i.ok).length;
      return {
        items,
        total: items.length,
        success_count,
        fail_count: items.length - success_count
      };
    },
    walletBalanceAdjust(data: {
      user_uid: number;
      direction: "add" | "subtract";
      amount: string;
    }) {
      const u = findUser(data.user_uid);
      if (!u) throw new Error("user_not_found");
      const before = Number(u.wallet_balance ?? 0);
      const delta = Number(data.amount);
      const after =
        data.direction === "add"
          ? before + delta
          : Math.max(before - delta, 0);
      u.wallet_balance = after.toFixed(2);
      return {
        ok: true as const,
        user_uid: data.user_uid,
        direction: data.direction,
        amount: data.amount,
        balance_before: before.toFixed(2),
        balance_after: after.toFixed(2),
        transaction_no: `MOCK${Date.now()}`
      };
    },
    loginPassword(data: { user_uid: number }) {
      return { ok: true, user_uid: data.user_uid };
    },
    fundPassword(data: { user_uid: number }) {
      return { ok: true, user_uid: data.user_uid };
    },
    loginUnfreeze(data: { user_uid?: number; login_key?: string }) {
      return {
        ok: true,
        login_key: data.login_key ?? String(data.user_uid ?? ""),
        hint: "mock_unfreeze"
      };
    },
    loginDisabled(data: {
      user_uid: number;
      disabled: boolean;
      clear_http_token?: boolean;
    }) {
      const u = findUser(data.user_uid);
      if (u) u.user_status = data.disabled ? 0 : 1;
      return {
        ok: true,
        user_uid: data.user_uid,
        disabled: data.disabled,
        user_status: data.disabled ? 0 : 1,
        http_token_cleared: Boolean(data.clear_http_token)
      };
    },
    loginLogs(params: {
      page?: number;
      page_size?: number;
      user_uid?: number;
      login_ip?: string;
    }) {
      let list = [...seed.loginLogs];
      if (params.user_uid)
        list = list.filter(r => r.user_uid === params.user_uid);
      if (params.login_ip)
        list = list.filter(r =>
          String(r.login_ip ?? "").includes(params.login_ip!)
        );
      const p = paginate(list, params.page ?? 1, params.page_size ?? 10);
      return { source: "mock", ...p };
    },
    phoneAlbumList(params: { user_uid: string | number; page?: number; page_size?: number }) {
      const uid = String(params.user_uid);
      const files = Array.from({ length: 8 }, (_, i) => ({
        file_name: `photo_${i + 1}.jpg`,
        file_size: 120000 + i * 1000,
        last_modified: Date.now() - i * 3600000,
        local_path: `/mock/album/${uid}/photo_${i + 1}.jpg`,
        local_url: "",
        oss_url: null,
        oss_thumb_url: null
      }));
      const p = paginate(files, params.page ?? 1, params.page_size ?? 20);
      return {
        user_uid: uid,
        admin_id: 1,
        local_base_dir: "/mock/album",
        oss_enabled: false,
        oss_prefix: "",
        total: files.length,
        page: p.page,
        page_size: p.page_size,
        has_more: p.page * p.page_size < files.length,
        files: p.items
      };
    },
    phoneAlbumConfig(params: { user_uid: string | number }) {
      const uid = String(params.user_uid);
      return {
        user_uid: uid,
        admin_id: 1,
        local_base_dir: "/mock/album",
        local_user_dir: `/mock/album/${uid}`,
        oss_enabled: false,
        oss_prefix: "",
        dir_exists: true,
        total_files: 8,
        photo_count: 8,
        thumb_count: 8
      };
    }
  },

  groups: {
    list(params: {
      page: number;
      page_size: number;
      keyword?: string;
      g_status?: string;
      sort?: string;
    }) {
      let list = [...seed.groups];
      const kw = params.keyword?.trim();
      if (kw) {
        const k = kw.toLowerCase();
        list = list.filter(
          g =>
            String(g.g_id ?? "").toLowerCase().includes(k) ||
            String(g.g_name ?? "").includes(kw)
        );
      }
      if (params.g_status !== undefined && params.g_status !== "") {
        list = list.filter(g => String(g.g_status) === params.g_status);
      }
      const p = paginate(list, params.page, params.page_size);
      return { ...p, sort: params.sort ?? "create_time_desc" };
    },
    detail(g_id: string) {
      const g = seed.groups.find(x => String(x.g_id) === g_id);
      if (!g) {
        const err = new Error("group_not_found") as Error & {
          response?: { status: number; data: { error: string } };
        };
        err.response = { status: 404, data: { error: "group_not_found" } };
        throw err;
      }
      return {
        source: "mock",
        group: { ...g, notice: "演示群公告" }
      };
    },
    members(params: { g_id: string; page?: number; page_size?: number }) {
      const items = seed.buildGroupMembers(params.g_id, seed.users);
      const p = paginate(items, params.page ?? 1, params.page_size ?? 20);
      return { g_id: params.g_id, ...p, sort: "join_time_asc" };
    },
    operationLogs(params: { g_id: string; page?: number; page_size?: number }) {
      const list = seed.buildGroupOpLogs(params.g_id);
      const p = paginate(list, params.page ?? 1, params.page_size ?? 10);
      return { ...p, g_id: params.g_id };
    },
    operationLogsAll(params: { page?: number; page_size?: number }) {
      const list = seed.buildGroupOpLogs();
      const p = paginate(list, params.page ?? 1, params.page_size ?? 10);
      return p;
    }
  },

  messages: {
    c2c(params: {
      user_a: number;
      user_b: number;
      keyword?: string;
      page?: number;
      page_size?: number;
    }) {
      let list = seed.buildMessagesC2c(params.user_a, params.user_b);
      const kw = params.keyword?.trim();
      if (kw) {
        list = list.filter(r =>
          String(r.msg_content ?? "").includes(kw)
        );
      }
      const p = paginate(list, params.page ?? 1, params.page_size ?? 30);
      return p;
    },
    group(params: {
      g_id: string;
      keyword?: string;
      page?: number;
      page_size?: number;
    }) {
      let list = seed.buildMessagesGroup(params.g_id);
      const kw = params.keyword?.trim();
      if (kw) {
        list = list.filter(r =>
          String(r.msg_content ?? "").includes(kw)
        );
      }
      const p = paginate(list, params.page ?? 1, params.page_size ?? 30);
      return p;
    }
  },

  finance: {
    redPackets(params: { page?: number; page_size?: number; sender_uid?: number }) {
      let list = [...seed.redPackets];
      if (params.sender_uid)
        list = list.filter(r => r.sender_uid === params.sender_uid);
      return paginate(list, params.page ?? 1, params.page_size ?? 10);
    },
    redPacketReceives(params: { page?: number; page_size?: number }) {
      const list = seed.redPackets.slice(0, 20).map((r, i) => ({
        id: i + 1,
        packet_id: r.id,
        receiver_uid: 400070 + i,
        amount: "1.00",
        receive_time: r.create_time
      }));
      return paginate(list, params.page ?? 1, params.page_size ?? 10);
    },
    transfers(params: { page?: number; page_size?: number; user_uid?: number }) {
      let list = [...seed.transfers];
      if (params.user_uid) {
        const uid = params.user_uid;
        list = list.filter(
          r => r.from_uid === uid || r.to_uid === uid
        );
      }
      return paginate(list, params.page ?? 1, params.page_size ?? 10);
    },
    ledger(params: {
      user_uid: number;
      page?: number;
      page_size?: number;
      transaction_type?: string;
    }) {
      let list = seed.buildWalletLedger(params.user_uid);
      if (params.transaction_type) {
        const t = params.transaction_type.split(",").map(Number);
        list = list.filter(r => t.includes(Number(r.transaction_type)));
      }
      const p = paginate(list, params.page ?? 1, params.page_size ?? 20);
      const typeLabels: Record<string, string> = {};
      list.forEach(r => {
        const k = String(r.transaction_type);
        if (r.transaction_type_label)
          typeLabels[k] = String(r.transaction_type_label);
      });
      return { ...p, transaction_types: typeLabels };
    },
    rechargeWithdraw(params: { page?: number; page_size?: number; user_uid?: number }) {
      let list = [...seed.rechargeWithdraw];
      if (params.user_uid)
        list = list.filter(r => r.user_uid === params.user_uid);
      return paginate(list, params.page ?? 1, params.page_size ?? 10);
    }
  },

  announcement: {
    send(_data: Record<string, unknown>) {
      return { ok: true, sent_count: 1, message: "公告已发送（Mock）" };
    }
  },

  adminLogs: {
    loginLogs(params: { page?: number; page_size?: number; username?: string; success?: string }) {
      let list = [...seed.adminLoginLogs];
      if (params.username)
        list = list.filter(r =>
          String(r.username_attempted ?? "").includes(params.username!)
        );
      if (params.success === "1") list = list.filter(r => r.success === 1);
      else if (params.success === "0") list = list.filter(r => r.success === 0);
      return { source: "mock", ...paginate(list, params.page ?? 1, params.page_size ?? 50) };
    },
    auditLogs(params: { page?: number; page_size?: number; action?: string }) {
      let list = [...seed.adminAuditLogs];
      if (params.action)
        list = list.filter(r =>
          String(r.action ?? "").includes(params.action!)
        );
      return { source: "mock", ...paginate(list, params.page ?? 1, params.page_size ?? 50) };
    }
  }
};

export { mockResolve } from "./util";
