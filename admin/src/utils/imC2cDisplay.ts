/**
 * 单聊消息列表：标注发送侧（相对 user_a/user_b 或 本人/好友）
 */

export type C2cSenderLabelStyle = "ab" | "selfPeer";

export function formatC2cSenderLabel(
  srcUid: unknown,
  anchorLeft: string,
  anchorRight: string,
  style: C2cSenderLabelStyle
): string {
  const src = srcUid == null || srcUid === "" ? "" : String(srcUid).trim();
  const left = String(anchorLeft).trim();
  const right = String(anchorRight).trim();
  if (!left || !right) {
    return src ? `IM ${src}` : "—";
  }
  if (!src) return "—";

  if (style === "ab") {
    if (src === left) return `A 方（${left}）`;
    if (src === right) return `B 方（${right}）`;
    return `其它（${src}）`;
  }

  if (src === left) return `本人（${left}）`;
  if (src === right) return `对方（${right}）`;
  return `其它（${src}）`;
}

export function pickMsgSrcUid(row: Record<string, unknown>): unknown {
  return (
    row.src_uid ??
    row.from_uid ??
    row.sender_uid ??
    row.from_account ??
    row.fromAccount
  );
}
