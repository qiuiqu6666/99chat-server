import type { AdminCreateByCountAccount } from "@/api/im-user";

/** 账号列表 TSV（含表头），便于粘贴到 Excel */
export function formatCreateAccountsTsv(
  accounts: AdminCreateByCountAccount[]
): string {
  const header = "昵称\tIM号\t密码\t充值地址";
  const lines = accounts.map(a =>
    [
      a.nickname,
      a.user_uid,
      a.password || "",
      a.deposit_address || a.trx_address || ""
    ].join("\t")
  );
  return [header, ...lines].join("\n");
}

/** 单行账号摘要 */
export function formatCreateAccountLine(a: AdminCreateByCountAccount): string {
  return `${a.nickname}\t${a.user_uid}\t${a.password || ""}\t${a.deposit_address || a.trx_address || ""}`;
}
