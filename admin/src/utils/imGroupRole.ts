/** 群成员角色：0 普通成员、1 管理员、2 群主 */
const GROUP_MEMBER_ROLE_LABELS: Record<number, string> = {
  0: "普通成员",
  1: "管理员",
  2: "群主"
};

export function formatGroupMemberRole(raw: unknown): string {
  if (raw === null || raw === undefined || raw === "") return "—";
  const n = Number(raw);
  if (!Number.isFinite(n)) return String(raw);
  return GROUP_MEMBER_ROLE_LABELS[n] ?? `角色(${n})`;
}
