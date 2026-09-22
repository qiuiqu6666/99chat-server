import { ElMessageBox } from "element-plus";
import {
  postUserLoginDisabled,
  adminApiErrMessage,
  normalizeImUserUid
} from "@/api/im-user";
import { message } from "@/utils/message";

/**
 * 「禁用 / 允许登录」：`POST /api/v1/users/login-disabled`
 * @returns 是否已成功提交并应由调用方刷新数据
 */
export async function toggleImUserLoginDisabledWithConfirm(opts: {
  user_uid: string;
  nickname?: string | number | unknown;
  userStatusRaw: number | unknown;
}): Promise<boolean> {
  const uid = normalizeImUserUid(opts.user_uid);
  if (!uid) {
    message("缺少有效 IM 号（user_uid）", { type: "warning" });
    return false;
  }

  const nick = opts.nickname != null ? String(opts.nickname) : "";
  const ust = Number(opts.userStatusRaw);
  const isNormalLogin = ust === 1;

  if (isNormalLogin) {
    try {
      await ElMessageBox.confirm(
        `将禁止用户登录（user_status=0），并清空信任设备以登出多端。\n目标：${nick}（${uid}）`,
        "禁用账号登录",
        {
          type: "warning",
          confirmButtonText: "确认禁用",
          cancelButtonText: "取消"
        }
      );
    } catch {
      return false;
    }
  } else {
    try {
      await ElMessageBox.confirm(
        `允许该用户登录（user_status 恢复为 1）。\n目标：${nick}（${uid}）`,
        "允许账号登录",
        {
          type: "warning",
          confirmButtonText: "确认解禁",
          cancelButtonText: "取消"
        }
      );
    } catch {
      return false;
    }
  }

  try {
    if (isNormalLogin) {
      await postUserLoginDisabled({
        user_uid: uid,
        disabled: true,
        clear_http_token: true
      });
      message("已禁用登录", { type: "success" });
    } else {
      await postUserLoginDisabled({
        user_uid: uid,
        disabled: false
      });
      message("已允许登录", { type: "success" });
      message("请通知用户在 App 内退出账号后重新登录", {
        type: "info",
        duration: 6000
      });
    }
    return true;
  } catch (err: unknown) {
    const ax = err as {
      response?: { status?: number; data?: { error?: string } };
    };
    if (
      ax?.response?.status === 403 ||
      ax?.response?.data?.error === "forbidden"
    ) {
      message("无权限（需要 user.write）", { type: "error" });
      return false;
    }
    if (
      ax?.response?.status === 404 ||
      ax?.response?.data?.error === "user_not_found"
    ) {
      message("用户不存在", { type: "warning" });
      return false;
    }
    message(adminApiErrMessage(err, "操作失败"), { type: "error" });
    return false;
  }
}
