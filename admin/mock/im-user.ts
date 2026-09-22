import { defineFakeRoute } from "vite-plugin-fake-server/client";
import { faker } from "@faker-js/faker/locale/zh_CN";

function buildMockUsers(total: number) {
  const list: Array<{
    id: number;
    uid: string;
    avatar: string;
    nickname: string;
    phone: string;
    signature: string;
    status: number;
    lastLoginTime: number;
    deviceCount: number;
    loginDevices: string;
    loginIp: string;
    /** 设备指纹 / 风控侧设备 ID（演示 Mock） */
    deviceId: string;
    /** 是否与 IM 链路在线（演示 Mock） */
    online: boolean;
    inWhitelist: boolean;
    adminRemark: string;
    balance: number;
    email: string;
    registrationTime: number;
    region: string;
  }> = [];

  const statuses = [1, 1, 1, 1, 2, 3, 4];

  for (let i = 1; i <= total; i++) {
    list.push({
      id: i,
      uid: `IM${String(1000000 + i).slice(1)}`,
      avatar: `https://avatars.githubusercontent.com/u/${10000 + i}?v=4`,
      nickname: faker.person.lastName() + faker.string.alphanumeric(3),
      phone: faker.helpers.fromRegExp(/1[3-9]\d{9}/),
      signature:
        faker.helpers.maybe(() => faker.lorem.sentence(), { probability: 0.6 }) ||
        "",
      status: faker.helpers.arrayElement(statuses),
      lastLoginTime: faker.date.recent({ days: 60 }).getTime(),
      deviceCount: faker.number.int({ min: 1, max: 4 }),
      loginDevices: (() => {
        const pool = ["Android", "iOS", "Windows", "Mac", "Web浏览器"];
        const n = faker.number.int({ min: 1, max: 3 });
        return faker.helpers
          .shuffle([...pool])
          .slice(0, n)
          .sort()
          .join(" · ");
      })(),
      loginIp: faker.internet.ipv4(),
      deviceId: `fp_${faker.string.hexadecimal({ length: 24, casing: "lower", prefix: "" })}`,
      online: Math.random() > 0.48,
      inWhitelist: Math.random() < 0.08,
      adminRemark: "",
      balance: Math.round(Math.random() * 5000000) / 100,
      email: faker.internet.email({ provider: "qq.com" }),
      registrationTime: faker.date.past({ years: 2 }).getTime(),
      region: faker.location.state()
    });
  }

  return list;
}

function injectSharedIpAndDeviceDummy(
  list: Array<{
    loginIp: string;
    deviceId: string;
    loginDevices: string;
  }>
) {
  const ips = ["103.236.254.91", "36.148.211.74", "47.245.93.210"];
  ips.forEach((ip, gi) => {
    for (let j = 0; j < 3; j++) {
      const u = list[gi * 11 + j + 2];
      if (u) u.loginIp = ip;
    }
  });
  const devs = [
    {
      deviceId: "fp_sm_demo_7a9012bcef",
      label: "Android App · 红米 Note"
    },
    {
      deviceId: "fp_sm_demo_8833ccddee",
      label: "iOS App · iPhone"
    },
    {
      deviceId: "fp_pc_demo_ddee0011",
      label: "Web浏览器 · Chrome / Windows"
    }
  ];
  devs.forEach((d, di) => {
    for (let j = 0; j < 4; j++) {
      const u = list[5 + di * 12 + j];
      if (u) {
        u.deviceId = d.deviceId;
        u.loginDevices = d.label;
      }
    }
  });
}

const allUsers = buildMockUsers(86);
injectSharedIpAndDeviceDummy(allUsers);

function toAccountBrief(u: (typeof allUsers)[number]) {
  return {
    id: u.id,
    uid: u.uid,
    nickname: u.nickname,
    status: u.status,
    lastLoginTime: u.lastLoginTime,
    loginIp: u.loginIp,
    loginDevices: u.loginDevices,
    deviceId: u.deviceId,
    online: u.online
  };
}

export default defineFakeRoute([
  {
    url: "/im-user/list",
    method: "post",
    response: ({ body }) => {
      const nickname = body?.nickname ? String(body.nickname).trim() : "";
      const uid = body?.uid ? String(body.uid).trim() : "";
      const phone = body?.phone ? String(body.phone).trim() : "";
      const status = body?.status;
      const pageSize = Math.min(Number(body?.pageSize) || 10, 100);
      const currentPage = Math.max(Number(body?.currentPage) || 1, 1);

      let list = [...allUsers];
      if (nickname) {
        list = list.filter(row => row.nickname.includes(nickname));
      }
      if (uid) {
        list = list.filter(row =>
          row.uid.toLowerCase().includes(uid.toLowerCase())
        );
      }
      if (phone) {
        list = list.filter(row => row.phone.includes(phone));
      }
      if (status !== undefined && status !== "" && status !== null) {
        list = list.filter(row => row.status === Number(status));
      }

      const total = list.length;
      const start = (currentPage - 1) * pageSize;
      const pageData = list.slice(start, start + pageSize);

      return {
        code: 0,
        message: "success",
        data: {
          list: pageData,
          total,
          pageSize,
          currentPage
        }
      };
    }
  },
  {
    url: "/im-user/detail",
    method: "post",
    response: ({ body }) => {
      const id = Number(body?.id);
      if (!Number.isFinite(id)) {
        return { code: 400, message: "缺少合法的用户 id" };
      }
      const row = allUsers.find(u => u.id === id);
      if (!row) {
        return { code: 1, message: "用户不存在" };
      }
      const loginPwdAt = Reflect.get(row, "_mockLoginPwdChangedAt") as number | undefined;
      const tradePwdAt = Reflect.get(row, "_mockTradePwdChangedAt") as number | undefined;
      return {
        code: 0,
        message: "success",
        data: {
          ...row,
          loginPasswordChangedAt: loginPwdAt,
          transactionPasswordChangedAt: tradePwdAt
        }
      };
    }
  },
  {
    url: "/im-user/same-login-ip",
    method: "post",
    response: ({ body }) => {
      const minCount = Math.max(2, Math.min(30, Number(body?.minCount) || 2));
      const kw = body?.keyword != null ? String(body.keyword).trim() : "";
      const byIp = new Map<string, (typeof allUsers)[number][]>();
      for (const u of allUsers) {
        const ip = u.loginIp || "";
        if (kw && !ip.includes(kw)) continue;
        const cur = byIp.get(ip) ?? [];
        cur.push(u);
        byIp.set(ip, cur);
      }
      const list = [...byIp.entries()]
        .filter(([, arr]) => arr.length >= minCount)
        .map(([loginIp, arr]) => ({
          key: loginIp,
          subtitle: "最近登录 IP 相同",
          accountCount: arr.length,
          accounts: [...arr]
            .sort((a, b) => b.lastLoginTime - a.lastLoginTime)
            .map(toAccountBrief)
        }))
        .sort((a, b) => b.accountCount - a.accountCount);
      return {
        code: 0,
        message: "success",
        data: { list }
      };
    }
  },
  {
    url: "/im-user/same-device-id",
    method: "post",
    response: ({ body }) => {
      const minCount = Math.max(2, Math.min(30, Number(body?.minCount) || 2));
      const kw = body?.keyword != null ? String(body.keyword).trim() : "";
      const byDev = new Map<string, (typeof allUsers)[number][]>();
      for (const u of allUsers) {
        const did = u.deviceId || "";
        if (
          kw &&
          !(
            did.toLowerCase().includes(kw.toLowerCase()) ||
            u.uid.toLowerCase().includes(kw.toLowerCase())
          )
        ) {
          continue;
        }
        const cur = byDev.get(did) ?? [];
        cur.push(u);
        byDev.set(did, cur);
      }
      const list = [...byDev.entries()]
        .filter(([, arr]) => arr.length >= minCount)
        .map(([deviceId, arr]) => {
          const pick = arr.reduce((a, b) =>
            a.lastLoginTime >= b.lastLoginTime ? a : b
          );
          return {
            key: deviceId,
            subtitle: pick.loginDevices || "—",
            accountCount: arr.length,
            accounts: [...arr]
              .sort((a, b) => b.lastLoginTime - a.lastLoginTime)
              .map(toAccountBrief)
          };
        })
        .sort((a, b) => b.accountCount - a.accountCount);
      return {
        code: 0,
        message: "success",
        data: { list }
      };
    }
  },
  {
    url: "/im-user/update",
    method: "post",
    response: ({ body }) => {
      const id = Number(body?.id);
      const idx = allUsers.findIndex(u => u.id === id);
      if (idx === -1) {
        return { code: 1, message: "用户不存在" };
      }
      const row = allUsers[idx];
      if (body?.nickname !== undefined) {
        row.nickname = String(body?.nickname ?? row.nickname);
      }
      if (body?.phone !== undefined) {
        row.phone = String(body?.phone ?? row.phone).replace(/\D/g, "").slice(0, 11);
        if (!/^1[3-9]\d{9}$/.test(row.phone)) {
          return { code: 400, message: "手机号格式不正确" };
        }
      }
      if (body?.signature !== undefined) {
        row.signature = String(body?.signature ?? "");
      }
      if (
        body?.status !== undefined &&
        body?.status !== "" &&
        body?.status !== null
      ) {
        const s = Number(body.status);
        if (!Number.isNaN(s)) row.status = s;
      }
      if (body?.inWhitelist !== undefined) {
        row.inWhitelist = Boolean(body.inWhitelist);
      }
      if (body?.adminRemark !== undefined) {
        row.adminRemark = String(body?.adminRemark ?? "");
      }
      // 密码仅示意：不向列表回显明文，真实环境由服务端写入凭证表
      if (body?.loginPassword != null && String(body.loginPassword).trim() !== "") {
        Reflect.set(row, "_mockLoginPwdChangedAt", Date.now());
      }
      if (
        body?.transactionPassword != null &&
        String(body.transactionPassword).trim() !== ""
      ) {
        Reflect.set(row, "_mockTradePwdChangedAt", Date.now());
      }
      return { code: 0, message: "操作成功" };
    }
  },
  {
    url: "/im-user/balance-adjust",
    method: "post",
    response: ({ body }) => {
      const id = Number(body?.id);
      const idx = allUsers.findIndex(u => u.id === id);
      if (idx === -1) {
        return { code: 1, message: "用户不存在" };
      }
      const bal = Number(body?.balance);
      if (!Number.isFinite(bal) || bal < 0 || bal > 999999999.99) {
        return { code: 400, message: "余额无效或超出范围" };
      }
      allUsers[idx].balance = Math.round(bal * 100) / 100;
      return { code: 0, message: "操作成功" };
    }
  }
]);
