import { defineFakeRoute } from "vite-plugin-fake-server/client";
import { faker } from "@faker-js/faker/locale/zh_CN";

const rpTypes = ["私聊红包", "群红包", "专属红包"];
const rpStatus = ["待领取", "已领完", "已退回", "已过期"] as const;
const xferStatus = ["成功", "处理中", "失败"] as const;
const bwTypes = ["充值", "提现"] as const;
const bwChannel = ["微信", "支付宝", "银联", "内部划转"];
const bwStatus = ["成功", "处理中", "失败"] as const;

function imUid(seed: number) {
  return `IM${String(1000000 + (seed % 900) + 1).slice(1)}`;
}

function buildRedPacketLogs(total: number) {
  const rows: Array<{
    id: number;
    orderNo: string;
    uid: string;
    nickname: string;
    packetType: string;
    targetSummary: string;
    totalAmount: number;
    grabbedCount: number;
    packetCount: number;
    status: (typeof rpStatus)[number];
    createdAt: number;
  }> = [];
  for (let i = 1; i <= total; i++) {
    const pc = faker.number.int({ min: 3, max: 20 });
    const gc = faker.number.int({ min: 0, max: pc });
    rows.push({
      id: i,
      orderNo: `RP${faker.string.numeric({ length: 14 })}`,
      uid: imUid(i * 7),
      nickname: faker.person.lastName() + faker.string.alphanumeric(2),
      packetType: faker.helpers.arrayElement(rpTypes),
      targetSummary:
        faker.helpers.arrayElement(["私聊", "群"]) +
        faker.string.numeric({ length: 6 }),
      totalAmount: Number(faker.finance.amount({ min: 0.01, max: 8888, dec: 2 })),
      grabbedCount: gc,
      packetCount: pc,
      status: faker.helpers.arrayElement(rpStatus),
      createdAt: faker.date.recent({ days: 30 }).getTime()
    });
  }
  rows.sort((a, b) => b.createdAt - a.createdAt);
  return rows;
}

function buildTransferLogs(total: number) {
  const rows: Array<{
    id: number;
    tradeNo: string;
    fromUid: string;
    fromNickname: string;
    toUid: string;
    toNickname: string;
    amount: number;
    fee: number;
    memo: string;
    status: (typeof xferStatus)[number];
    createdAt: number;
  }> = [];
  for (let i = 1; i <= total; i++) {
    const ok = faker.helpers.arrayElement(xferStatus);
    rows.push({
      id: i,
      tradeNo: `XF${faker.string.numeric({ length: 16 })}`,
      fromUid: imUid(i * 11),
      fromNickname: faker.person.fullName(),
      toUid: imUid(i * 13 + 1),
      toNickname: faker.person.fullName(),
      amount: Number(faker.finance.amount({ min: 0.01, max: 50000, dec: 2 })),
      fee: Number(faker.finance.amount({ min: 0, max: 5, dec: 2 })),
      memo:
        faker.helpers.maybe(() => faker.lorem.words(4), {
          probability: 0.4
        }) ?? "—",
      status: ok,
      createdAt: faker.date.recent({ days: 45 }).getTime()
    });
  }
  rows.sort((a, b) => b.createdAt - a.createdAt);
  return rows;
}

function buildRechargeWithdraw(total: number) {
  const rows: Array<{
    id: number;
    bizNo: string;
    uid: string;
    nickname: string;
    bizType: (typeof bwTypes)[number];
    channel: string;
    amount: number;
    status: (typeof bwStatus)[number];
    externalOrderNo: string;
    createdAt: number;
  }> = [];
  for (let i = 1; i <= total; i++) {
    rows.push({
      id: i,
      bizNo: `BW${faker.string.numeric({ length: 15 })}`,
      uid: imUid(i * 17),
      nickname: faker.person.lastName() + faker.string.numeric(2),
      bizType: faker.helpers.arrayElement(bwTypes),
      channel: faker.helpers.arrayElement(bwChannel),
      amount: Number(faker.finance.amount({ min: 1, max: 20000, dec: 2 })),
      status: faker.helpers.arrayElement(bwStatus),
      externalOrderNo: faker.string.alphanumeric({ length: 18 }).toUpperCase(),
      createdAt: faker.date.recent({ days: 60 }).getTime()
    });
  }
  rows.sort((a, b) => b.createdAt - a.createdAt);
  return rows;
}

const redPacketLogs = buildRedPacketLogs(240);
const transferLogs = buildTransferLogs(260);
const rechargeWithdrawLogs = buildRechargeWithdraw(280);

function paginate<T>(
  list: T[],
  body: any
): {
  list: T[];
  total: number;
  pageSize: number;
  currentPage: number;
} {
  const pageSize = Math.min(Number(body?.pageSize) || 10, 100);
  const currentPage = Math.max(Number(body?.currentPage) || 1, 1);
  const total = list.length;
  const start = (currentPage - 1) * pageSize;
  return {
    list: list.slice(start, start + pageSize),
    total,
    pageSize,
    currentPage
  };
}

export default defineFakeRoute([
  {
    url: "/im-account/red-packet-logs",
    method: "post",
    response: ({ body }) => {
      const uid = body?.uid ? String(body.uid).trim() : "";
      const keyword =
        body?.keyword != null ? String(body.keyword).trim() : "";
      const packetType =
        body?.packetType ? String(body.packetType).trim() : "";
      const status = body?.status ? String(body.status).trim() : "";

      let list = [...redPacketLogs];
      if (uid) {
        const u = uid.toLowerCase();
        list = list.filter(
          r =>
            r.uid.toLowerCase().includes(u) ||
            r.nickname.includes(uid) ||
            r.orderNo.toLowerCase().includes(u)
        );
      }
      if (keyword) {
        const k = keyword.toLowerCase();
        list = list.filter(
          r =>
            r.targetSummary.includes(keyword) ||
            r.packetType.includes(keyword) ||
            r.orderNo.toLowerCase().includes(k)
        );
      }
      if (packetType) list = list.filter(r => r.packetType === packetType);
      if (status) list = list.filter(r => r.status === status);

      return {
        code: 0,
        message: "success",
        data: paginate(list, body)
      };
    }
  },
  {
    url: "/im-account/transfer-logs",
    method: "post",
    response: ({ body }) => {
      const uid = body?.uid ? String(body.uid).trim() : "";
      const keyword =
        body?.keyword != null ? String(body.keyword).trim() : "";
      const status = body?.status ? String(body.status).trim() : "";

      let list = [...transferLogs];
      if (uid) {
        const u = uid.toLowerCase();
        list = list.filter(
          r =>
            r.fromUid.toLowerCase().includes(u) ||
            r.toUid.toLowerCase().includes(u) ||
            r.fromNickname.includes(uid) ||
            r.toNickname.includes(uid) ||
            r.tradeNo.toLowerCase().includes(u)
        );
      }
      if (keyword) {
        const k = keyword.toLowerCase();
        list = list.filter(
          r =>
            r.memo.toLowerCase().includes(k) ||
            r.tradeNo.toLowerCase().includes(k)
        );
      }
      if (status) list = list.filter(r => r.status === status);

      return {
        code: 0,
        message: "success",
        data: paginate(list, body)
      };
    }
  },
  {
    url: "/im-account/recharge-withdraw-logs",
    method: "post",
    response: ({ body }) => {
      const uid = body?.uid ? String(body.uid).trim() : "";
      const keyword =
        body?.keyword != null ? String(body.keyword).trim() : "";
      const bizType = body?.bizType ? String(body.bizType).trim() : "";
      const status = body?.status ? String(body.status).trim() : "";

      let list = [...rechargeWithdrawLogs];
      if (uid) {
        const u = uid.toLowerCase();
        list = list.filter(
          r =>
            r.uid.toLowerCase().includes(u) ||
            r.nickname.includes(uid) ||
            r.bizNo.toLowerCase().includes(u)
        );
      }
      if (keyword) {
        const k = keyword.toLowerCase();
        list = list.filter(
          r =>
            r.channel.includes(keyword) ||
            r.externalOrderNo.toLowerCase().includes(k) ||
            r.bizNo.toLowerCase().includes(k)
        );
      }
      if (bizType) list = list.filter(r => r.bizType === bizType);
      if (status) list = list.filter(r => r.status === status);

      return {
        code: 0,
        message: "success",
        data: paginate(list, body)
      };
    }
  }
]);
