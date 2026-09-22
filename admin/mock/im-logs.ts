import { defineFakeRoute } from "vite-plugin-fake-server/client";
import { faker } from "@faker-js/faker/locale/zh_CN";

const clients = ["Android", "iOS", "Web", "Windows", "Mac"];

function buildLoginLogs(total: number) {
  const rows: Array<{
    id: number;
    uid: string;
    nickname: string;
    loginAt: number;
    clientType: string;
    ip: string;
    ua: string;
    success: boolean;
    failReason?: string;
  }> = [];
  for (let i = 1; i <= total; i++) {
    const ok = Math.random() > 0.08;
    rows.push({
      id: i,
      uid: `IM${String(1000000 + (i % 86) + 1).slice(1)}`,
      nickname: faker.person.lastName() + faker.string.alphanumeric(2),
      loginAt: faker.date.recent({ days: 14 }).getTime(),
      clientType: faker.helpers.arrayElement(clients),
      ip: faker.internet.ipv4(),
      ua: `IM-App/${faker.system.semver()} (${faker.helpers.arrayElement([
        "Linux",
        "iOS 17",
        "Windows NT 10.0"
      ])})`,
      success: ok,
      failReason: ok
        ? undefined
        : faker.helpers.arrayElement(["密码错误", "验证码过期", "风控拦截", "设备未信任"])
    });
  }
  rows.sort((a, b) => b.loginAt - a.loginAt);
  return rows;
}

const groupActions = [
  "创建群组",
  "修改群名称",
  "修改群公告",
  "转让群主",
  "成员入群",
  "成员退群",
  "踢出成员",
  "全员禁言",
  "解除全员禁言",
  "解散群组"
];

function buildGroupLogs(total: number) {
  const rows: Array<{
    id: number;
    groupId: number;
    groupNo: string;
    groupName: string;
    action: string;
    actorUid: string;
    actorName: string;
    detail: string;
    createdAt: number;
  }> = [];
  for (let i = 1; i <= total; i++) {
    const gid = (i % 64) + 1;
    rows.push({
      id: i,
      groupId: gid,
      groupNo: `G${String(100000 + gid)}`,
      groupName:
        faker.helpers.arrayElement([
          "产品内测群",
          "用户反馈总群",
          "城市跑友会"
        ]) + faker.string.numeric(2),
      action: faker.helpers.arrayElement(groupActions),
      actorUid:
        Math.random() > 0.15
          ? `IM${String(1000000 + ((i * 3) % 86) + 1).slice(1)}`
          : "system",
      actorName:
        Math.random() > 0.15 ? faker.person.fullName() : "系统",
      detail: faker.lorem.sentence(),
      createdAt: faker.date.recent({ days: 30 }).getTime()
    });
  }
  rows.sort((a, b) => b.createdAt - a.createdAt);
  return rows;
}

const allLoginLogs = buildLoginLogs(220);
const allGroupLogs = buildGroupLogs(180);

export default defineFakeRoute([
  {
    url: "/im-account/login-logs",
    method: "post",
    response: ({ body }) => {
      const uid = body?.uid ? String(body.uid).trim() : "";
      const keyword =
        body?.keyword != null ? String(body.keyword).trim() : "";
      const success = body?.success;
      const pageSize = Math.min(Number(body?.pageSize) || 10, 100);
      const currentPage = Math.max(Number(body?.currentPage) || 1, 1);

      let list = [...allLoginLogs];
      if (uid) {
        list = list.filter(
          r =>
            r.uid.toLowerCase().includes(uid.toLowerCase()) ||
            r.nickname.includes(uid)
        );
      }
      if (keyword) {
        list = list.filter(
          r =>
            r.ip.includes(keyword) ||
            r.ua.toLowerCase().includes(keyword.toLowerCase()) ||
            r.clientType.includes(keyword)
        );
      }
      if (success === "1") list = list.filter(r => r.success);
      else if (success === "0") list = list.filter(r => !r.success);

      const total = list.length;
      const start = (currentPage - 1) * pageSize;
      return {
        code: 0,
        message: "success",
        data: {
          list: list.slice(start, start + pageSize),
          total,
          pageSize,
          currentPage
        }
      };
    }
  },
  {
    url: "/im-group/operation-logs",
    method: "post",
    response: ({ body }) => {
      const groupNo =
        body?.groupNo ? String(body.groupNo).trim() : "";
      const keyword =
        body?.keyword != null ? String(body.keyword).trim() : "";
      const action =
        body?.action != null ? String(body.action).trim() : "";
      const pageSize = Math.min(Number(body?.pageSize) || 10, 100);
      const currentPage = Math.max(Number(body?.currentPage) || 1, 1);

      let list = [...allGroupLogs];
      if (groupNo) {
        list = list.filter(r =>
          r.groupNo.toLowerCase().includes(groupNo.toLowerCase())
        );
      }
      if (keyword) {
        const k = keyword.toLowerCase();
        list = list.filter(
          r =>
            r.groupName.includes(keyword) ||
            r.detail.toLowerCase().includes(k) ||
            r.actorUid.toLowerCase().includes(k) ||
            r.actorName.includes(keyword)
        );
      }
      if (action) {
        list = list.filter(r => r.action === action || r.action.includes(action));
      }

      const total = list.length;
      const start = (currentPage - 1) * pageSize;
      return {
        code: 0,
        message: "success",
        data: {
          list: list.slice(start, start + pageSize),
          total,
          pageSize,
          currentPage
        }
      };
    }
  }
]);
