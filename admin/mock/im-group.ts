import { defineFakeRoute } from "vite-plugin-fake-server/client";
import { faker } from "@faker-js/faker/locale/zh_CN";

type GroupRow = {
  id: number;
  groupNo: string;
  name: string;
  ownerUid: string;
  memberCount: number;
  memberLimit: number;
  joinType: string;
  /** 1 正常 2 封禁 3 全员禁言 */
  status: number;
  notice: string;
  createTime: number;
};

const joinTypes = ["邀请审核", "自由加入", "口令入群", "仅邀请"];

function buildGroups(n: number): GroupRow[] {
  const rows: GroupRow[] = [];
  const statuses = [1, 1, 1, 2, 3];
  for (let i = 1; i <= n; i++) {
    rows.push({
      id: i,
      groupNo: `G${String(100000 + i)}`,
      name: faker.helpers.arrayElement([
        `${faker.company.name()}交流群`,
        `用户${faker.string.numeric(5)}官方群`,
        `${faker.person.lastName()}粉丝后援`
      ]),
      ownerUid: `IM${String(1000000 + ((i % 86) + 1)).slice(1)}`,
      memberCount: faker.number.int({ min: 5, max: 2000 }),
      memberLimit: faker.helpers.arrayElement([200, 500, 1000, 2000]),
      joinType: faker.helpers.arrayElement(joinTypes),
      status: faker.helpers.arrayElement(statuses),
      notice:
        faker.helpers.maybe(() => faker.lorem.sentence(), {
          probability: 0.55
        }) || "",
      createTime: faker.date.past({ years: 2 }).getTime()
    });
  }
  return rows;
}

const allGroups = buildGroups(64);

function buildMemberRows(
  g: GroupRow,
  keyword: string
): Array<{
  uid: string;
  nickname: string;
  role: string;
  joinTime: number;
  muted: boolean;
}> {
  faker.seed(g.id * 9991);
  const n = Math.min(Math.max(g.memberCount, 8), 48);
  const rows: Array<{
    uid: string;
    nickname: string;
    role: string;
    joinTime: number;
    muted: boolean;
  }> = [
    {
      uid: g.ownerUid,
      nickname: faker.person.fullName(),
      role: "群主",
      joinTime: g.createTime,
      muted: false
    }
  ];

  const admins = Math.min(3, Math.max(1, Math.floor(n / 12)));
  while (rows.length < 1 + admins) {
    rows.push({
      uid: `IM${String(1000000 + faker.number.int({ min: 1, max: 920 })).slice(1)}`,
      nickname: faker.person.fullName(),
      role: "管理员",
      joinTime: faker.date.recent({ days: 200 }).getTime(),
      muted: Math.random() < 0.06
    });
  }

  while (rows.length < n) {
    rows.push({
      uid: `IM${String(1000000 + faker.number.int({ min: 1, max: 920 })).slice(1)}`,
      nickname: faker.person.fullName(),
      role: "成员",
      joinTime: faker.date.recent({ days: 360 }).getTime(),
      muted: Math.random() < 0.09
    });
  }

  let filtered = rows;
  if (keyword) {
    const k = keyword.toLowerCase();
    filtered = rows.filter(
      r => r.uid.toLowerCase().includes(k) || r.nickname.includes(keyword)
    );
  }
  return filtered.sort((a, b) => a.joinTime - b.joinTime);
}

function buildMessagePool(gid: number): Array<{
  msgId: string;
  senderUid: string;
  senderName: string;
  msgType: string;
  content: string;
  sentAt: number;
}> {
  faker.seed(gid * 424242);
  const total = faker.number.int({ min: 40, max: 120 });
  const list: Array<{
    msgId: string;
    senderUid: string;
    senderName: string;
    msgType: string;
    content: string;
    sentAt: number;
  }> = [];

  const types = ["text", "text", "text", "image", "system"];
  for (let i = 0; i < total; i++) {
    const t = faker.helpers.arrayElement(types);
    list.push({
      msgId: `M_${gid}_${i}_${faker.string.alphanumeric(6)}`,
      senderUid: `IM${String(1000000 + faker.number.int({ min: 1, max: 850 })).slice(1)}`,
      senderName: faker.person.fullName(),
      msgType: t,
      content:
        t === "system"
          ? "[系统提示] " + faker.lorem.sentence()
          : t === "image"
            ? "[图片消息]"
            : faker.lorem.sentence(),
      sentAt: faker.date.recent({ days: 14 }).getTime()
    });
  }
  return list.sort((a, b) => b.sentAt - a.sentAt);
}

const messagePoolCache = new Map<number, ReturnType<typeof buildMessagePool>>();

function getMessagePool(gid: number) {
  if (!messagePoolCache.has(gid)) {
    messagePoolCache.set(gid, buildMessagePool(gid));
  }
  return messagePoolCache.get(gid)!;
}

export default defineFakeRoute([
  {
    url: "/im-group/list",
    method: "post",
    response: ({ body }) => {
      const nameKw = body?.name ? String(body.name).trim() : "";
      const groupNo = body?.groupNo ? String(body.groupNo).trim() : "";
      const ownerKw = body?.owner ? String(body.owner).trim() : "";
      const status = body?.status;
      const pageSize = Math.min(Number(body?.pageSize) || 10, 100);
      const currentPage = Math.max(Number(body?.currentPage) || 1, 1);

      let list = [...allGroups];
      if (nameKw) list = list.filter(r => r.name.includes(nameKw));
      if (groupNo) list = list.filter(r => r.groupNo.toLowerCase().includes(groupNo.toLowerCase()));
      if (ownerKw) {
        list = list.filter(r =>
          r.ownerUid.toLowerCase().includes(ownerKw.toLowerCase())
        );
      }
      if (status !== undefined && status !== "" && status !== null) {
        list = list.filter(r => r.status === Number(status));
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
  },
  {
    url: "/im-group/members",
    method: "post",
    response: ({ body }) => {
      const gid = Number(body?.groupId);
      const g = allGroups.find(x => x.id === gid);
      if (!g) {
        return { code: 1, message: "群不存在" };
      }
      const keyword = body?.keyword ? String(body.keyword).trim() : "";
      const list = buildMemberRows(g, keyword);
      return {
        code: 0,
        message: "ok",
        data: { list, total: list.length }
      };
    }
  },
  {
    url: "/im-group/messages",
    method: "post",
    response: ({ body }) => {
      const gid = Number(body?.groupId);
      if (!allGroups.some(x => x.id === gid)) {
        return { code: 1, message: "群不存在" };
      }
      const keyword = body?.keyword ? String(body.keyword).trim() : "";
      const pageSize = Math.min(Number(body?.pageSize) || 15, 100);
      const currentPage = Math.max(Number(body?.currentPage) || 1, 1);
      let pool = [...getMessagePool(gid)];
      if (keyword) {
        const k = keyword.toLowerCase();
        pool = pool.filter(
          m =>
            m.content.toLowerCase().includes(k) ||
            m.senderName.includes(keyword) ||
            m.senderUid.toLowerCase().includes(k)
        );
      }
      const total = pool.length;
      const start = (currentPage - 1) * pageSize;
      return {
        code: 0,
        message: "ok",
        data: {
          list: pool.slice(start, start + pageSize),
          total,
          pageSize,
          currentPage
        }
      };
    }
  }
]);
