import dayjs from "dayjs";
import { h, ref, reactive, onMounted, type Ref } from "vue";
import { useRouter } from "vue-router";
import { message } from "@/utils/message";
import userAvatar from "@/assets/user.jpg";
import type { AdminUserListItem } from "@/api/im-user";
import {
  getAdminUsers,
  mapAdminUserItemToTableRow,
  adminApiErrMessage,
  postWalletBalanceAdjust,
  postUserLoginPassword,
  postUserFundPassword,
  postUserNickname,
  postAdminUserGenerationTask,
  normalizeImUserUid,
  isImUserUid,
  type AdminCreateByCountBody
} from "@/api/im-user";
import { deviceDetection, copyTextToClipboard } from "@pureadmin/utils";
import type { PaginationProps } from "@pureadmin/table";
import { addDialog } from "@/components/ReDialog";
import ImAdjustBalanceForm from "../form/adjust-balance.vue";
import ImResetPasswordByAdmin from "../form/reset-password-by-admin.vue";
import ImEditNicknameByAdmin from "../form/edit-nickname-by-admin.vue";
import ImCreateUsersByCount from "../form/create-users-by-count.vue";
import {
  resolveAdjustFinalBalance,
  type ImAdjustBalanceFormInline
} from "./balanceAdjust";
import {
  buildWalletBalanceSnapshot,
  formatWalletAmountForApi,
  formatWalletCurrencyAmount,
  getAvailableBalanceForCurrency,
  type WalletCurrencyCode
} from "./walletCurrencyPolicy";
import type { ImAdminResetPwdFormInline } from "./adminResetPwdTypes";
import type { ImAdminEditNicknameFormInline } from "./adminEditNicknameTypes";
import { toggleImUserLoginDisabledWithConfirm } from "./toggleLoginDisabled";
import { hasPerms } from "@/utils/auth";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";

/** 与接口 `user_status` 一致：`1` 正常，`0` 禁用 */
const statusMap: Record<
  number,
  { label: string; type: "success" | "danger" | "warning" | "info" }
> = {
  1: { label: "正常", type: "success" },
  0: { label: "禁用", type: "danger" }
};

const USER_LIST_BALANCE_CURRENCIES: WalletCurrencyCode[] = ["USDT", "CNY"];

function renderUserListWalletBalance(row: Record<string, unknown>) {
  const raw = (row._raw ?? row) as Record<string, unknown>;
  const snapshot = buildWalletBalanceSnapshot(raw);

  return (
    <div class="leading-5 whitespace-nowrap">
      {USER_LIST_BALANCE_CURRENCIES.map((code, index) => (
        <div
          key={code}
          class={index === 0 ? "text-[13px]" : "text-xs text-gray-500"}
        >
          {formatWalletCurrencyAmount(
            code,
            getAvailableBalanceForCurrency(snapshot, code)
          )}
        </div>
      ))}
    </div>
  );
}
function renderTrxAddress(row: Record<string, unknown>) {
  const raw = (row._raw ?? row) as Record<string, unknown>;
  const address = String(row.trxAddress ?? raw.trx_address ?? raw.trxAddress ?? "").trim();
  if (!address) return <span class="text-gray-400">—</span>;

  const short = address.length > 18
    ? `${address.slice(0, 8)}...${address.slice(-6)}`
    : address;

  return (
    <div class="flex items-center justify-center gap-1 whitespace-nowrap">
      <el-tooltip content={address} placement="top">
        <span class="font-mono text-xs">{short}</span>
      </el-tooltip>
      <el-button
        link
        type="primary"
        size="small"
        onClick={() => copyTextToClipboard(address) && message("TRX 地址已复制", { type: "success" })}
      >
        复制
      </el-button>
    </div>
  );
}


export function useImUserList(tableRef: Ref) {
  const router = useRouter();
  const adjustBalanceFormRef = ref<{
    getRef: () => any;
    syncToOutbound: () => void;
  }>();
  const resetPwdFormRef = ref<{
    getRef: () => any;
    syncToOutbound: () => void;
  }>();
  const editNicknameFormRef = ref<{
    getRef: () => any;
    syncToOutbound: () => void;
  }>();
  const createByCountFormRef = ref<{
    getRef: () => any;
    syncToOutbound: () => void;
    getPayload: () => AdminCreateByCountBody;
  }>();
  const canUserWrite = hasPerms("user.write");

  function openCreateUsersByCount() {
    const initial = {
      password: "",
      count: 1
    };
    addDialog({
      title: "创建用户",
      props: { formInline: initial },
      width: "480px",
      draggable: true,
      fullscreen: deviceDetection(),
      fullscreenIcon: true,
      closeOnClickModal: false,
      contentRenderer: () =>
        h(ImCreateUsersByCount, {
          ref: createByCountFormRef,
          formInline: initial
        }),
      beforeSure: (done) => {
        const dlg = createByCountFormRef.value;
        if (!dlg) return;
        const formEl = dlg.getRef();
        formEl.validate(async (valid: boolean) => {
          if (!valid) return;
          dlg.syncToOutbound();
          try {
            const res = await postAdminUserGenerationTask(dlg.getPayload());
            message(`生成任务 ${res.task_no} 已提交`, { type: "success" });
            done();
            router.push({
              name: "ImUserGenerationTasks",
              query: { task_no: res.task_no }
            });
          } catch (err: unknown) {
            const ax = err as {
              response?: { status?: number; data?: { error?: string } };
            };
            const errCode = ax?.response?.data?.error;
            if (ax?.response?.status === 403 || errCode === "forbidden") {
              message("无权限（需要 user.write）", { type: "error" });
              return;
            }
            if (ax?.response?.status === 502 || errCode === "WALLET_NOT_CONFIGURED") {
              message("TRON 充值未配置（TRON_DEPOSIT_MNEMONIC）", { type: "error" });
              return;
            }
            message(adminApiErrMessage(err, "创建失败"), { type: "error" });
          }
        });
      }
    });
  }

  function buildUserUpdateSnapshot(row: any) {
    const st = row.status;
    const status =
      typeof st === "number" && (st === 0 || st === 1) ? st : 1;
    return {
      id: String(row.id ?? row.uid ?? ""),
      uid: row.uid ?? "",
      nickname: row.nickname ?? "",
      phone: row.phone ?? "",
      signature: row.signature ?? "",
      status,
      inWhitelist: !!row.inWhitelist,
      adminRemark: row.adminRemark ?? ""
    };
  }

  function openResetLoginPassword(row: any) {
    const snap = buildUserUpdateSnapshot(row);
    const initial: ImAdminResetPwdFormInline = {
      id: snap.id,
      uid: snap.uid,
      nickname: snap.nickname,
      kind: "login",
      password: "",
      passwordConfirm: ""
    };
    addDialog({
      title: `重置登录密码 · ${snap.nickname}（${snap.uid}）`,
      props: { formInline: initial },
      width: "480px",
      draggable: true,
      fullscreen: deviceDetection(),
      fullscreenIcon: true,
      closeOnClickModal: false,
      contentRenderer: () =>
        h(ImResetPasswordByAdmin, {
          ref: resetPwdFormRef,
          formInline: initial
        }),
      beforeSure: (done, { options }) => {
        const dlg = resetPwdFormRef.value;
        if (!dlg) return;
        const formEl = dlg.getRef();
        formEl.validate(async (valid: boolean) => {
          if (!valid) return;
          dlg.syncToOutbound();
          const raw = options.props!.formInline as ImAdminResetPwdFormInline;
          const pwd = raw.password.trim();
          try {
            await postUserLoginPassword({
              user_uid: snap.id,
              new_password: pwd
            });
            message("登录密码已重置", { type: "success" });
            done();
          } catch (err: unknown) {
            const ax = err as {
              response?: {
                status?: number;
                data?: { error?: string };
              };
            };
            if (
              ax?.response?.status === 403 ||
              ax?.response?.data?.error === "forbidden"
            ) {
              message("无权限（需要 user.write）", { type: "error" });
              return;
            }
            message(adminApiErrMessage(err, "操作失败"), { type: "error" });
          }
        });
      }
    });
  }

  function openEditNickname(row: any) {
    const snap = buildUserUpdateSnapshot(row);
    const initial: ImAdminEditNicknameFormInline = {
      id: snap.id,
      uid: snap.uid,
      nickname: snap.nickname
    };
    addDialog({
      title: `修改昵称 · ${snap.nickname}（${snap.uid}）`,
      props: { formInline: initial },
      width: "480px",
      draggable: true,
      fullscreen: deviceDetection(),
      fullscreenIcon: true,
      closeOnClickModal: false,
      contentRenderer: () =>
        h(ImEditNicknameByAdmin, {
          ref: editNicknameFormRef,
          formInline: initial
        }),
      beforeSure: (done, { options }) => {
        const dlg = editNicknameFormRef.value;
        if (!dlg) return;
        const formEl = dlg.getRef();
        formEl.validate(async (valid: boolean) => {
          if (!valid) return;
          dlg.syncToOutbound();
          const raw = options.props!.formInline as ImAdminEditNicknameFormInline;
          const nickname = raw.nickname.trim();
          if (!nickname) {
            message("请输入昵称", { type: "warning" });
            return;
          }
          try {
            await postUserNickname({
              user_uid: snap.id,
              nickname
            });
            message("昵称已更新（含 IM）", { type: "success" });
            done();
            onSearch();
          } catch (err: unknown) {
            const ax = err as {
              response?: {
                status?: number;
                data?: { error?: string; message?: string };
              };
            };
            const errCode = ax?.response?.data?.error;
            if (ax?.response?.status === 403 || errCode === "forbidden") {
              message("无权限（需要 user.write）", { type: "error" });
              return;
            }
            if (errCode === "duplicate_nickname") {
              message("昵称已被其他账号使用", { type: "warning" });
              return;
            }
            message(adminApiErrMessage(err, "修改失败"), { type: "error" });
          }
        });
      }
    });
  }

  function openResetTransactionPassword(row: any) {
    const snap = buildUserUpdateSnapshot(row);
    const initial: ImAdminResetPwdFormInline = {
      id: snap.id,
      uid: snap.uid,
      nickname: snap.nickname,
      kind: "transaction",
      password: "",
      passwordConfirm: ""
    };
    addDialog({
      title: `重置资金密码 · ${snap.nickname}（${snap.uid}）`,
      props: { formInline: initial },
      width: "480px",
      draggable: true,
      fullscreen: deviceDetection(),
      fullscreenIcon: true,
      closeOnClickModal: false,
      contentRenderer: () =>
        h(ImResetPasswordByAdmin, {
          ref: resetPwdFormRef,
          formInline: initial
        }),
      beforeSure: (done, { options }) => {
        const dlg = resetPwdFormRef.value;
        if (!dlg) return;
        const formEl = dlg.getRef();
        formEl.validate(async (valid: boolean) => {
          if (!valid) return;
          dlg.syncToOutbound();
          const raw = options.props!.formInline as ImAdminResetPwdFormInline;
          const pwd = raw.password.trim();
          try {
            await postUserFundPassword({
              user_uid: snap.id,
              new_fund_password: pwd
            });
            message("资金密码已设置", { type: "success" });
            done();
          } catch (err: unknown) {
            const ax = err as {
              response?: {
                status?: number;
                data?: { error?: string };
              };
            };
            if (
              ax?.response?.status === 403 ||
              ax?.response?.data?.error === "forbidden"
            ) {
              message("无权限（需要 user.write）", { type: "error" });
              return;
            }
            message(adminApiErrMessage(err, "操作失败"), { type: "error" });
          }
        });
      }
    });
  }

  async function confirmToggleLoginDisabled(row: Record<string, unknown>) {
    const ok =     await toggleImUserLoginDisabledWithConfirm({
      user_uid: normalizeImUserUid(row.id ?? row.uid),
      nickname: row.nickname,
      userStatusRaw: row.userStatusRaw
    });
    if (ok) onSearch();
  }

  function openAdjustBalance(row: any) {
    const raw = (row._raw ?? row) as Record<string, unknown>;
    const walletSnapshot = buildWalletBalanceSnapshot(raw);
    const currency = "USDT" as const;
    const avail = getAvailableBalanceForCurrency(walletSnapshot, currency);

    const initial: ImAdjustBalanceFormInline = {
      id: row.id,
      uid: row.uid ?? "",
      nickname: row.nickname ?? "",
      currency,
      walletSnapshot,
      initialBalance: Math.max(0, avail),
      balanceAdjustKind: "increase",
      balanceAdjustAmount: 0.01
    };

    addDialog({
      title: "调整余额",
      props: { formInline: initial },
      width: "400px",
      draggable: true,
      fullscreen: deviceDetection(),
      fullscreenIcon: false,
      closeOnClickModal: false,
      contentRenderer: () =>
        h(ImAdjustBalanceForm, {
          ref: adjustBalanceFormRef,
          formInline: initial
        }),
      beforeSure: (done, { options }) => {
        const dlg = adjustBalanceFormRef.value;
        if (!dlg) return;
        const formEl = dlg.getRef();
        formEl.validate(async (valid: boolean) => {
          if (!valid) return;
          dlg.syncToOutbound();
          const raw = options.props!.formInline as ImAdjustBalanceFormInline;
          const finalBalance = resolveAdjustFinalBalance(raw);
          if (!Number.isFinite(finalBalance) || finalBalance < 0) {
            message("余额计算异常", { type: "warning" });
            return;
          }
          if (finalBalance > 999999999.99) {
            message("调整后余额超出允许范围", { type: "warning" });
            return;
          }
          const direction =
            raw.balanceAdjustKind === "increase" ? "add" : "subtract";
          const amtRaw = Number(raw.balanceAdjustAmount);
          if (!Number.isFinite(amtRaw) || amtRaw <= 0) {
            message("金额无效", { type: "warning" });
            return;
          }
          const amountStr = formatWalletAmountForApi(raw.currency, amtRaw);
          try {
            const res = await postWalletBalanceAdjust({
              user_uid: raw.id,
              currency: raw.currency,
              direction,
              amount: amountStr
            });
            const cur = res.currency ?? raw.currency;
            const extra = res.transaction_no
              ? `，流水 ${res.transaction_no}`
              : "";
            message(
              `${cur} 余额已更新（${res.balance_before} → ${res.balance_after}）${extra}`,
              { type: "success" }
            );
            done();
            onSearch();
          } catch (err: unknown) {
            const ax = err as {
              response?: {
                status?: number;
                data?: { error?: string };
              };
            };
            const errCode = ax?.response?.data?.error;
            if (
              ax?.response?.status === 403 ||
              errCode === "forbidden"
            ) {
              message("无权限（需要 user.write）", { type: "error" });
              return;
            }
            if (errCode === "insufficient_available_balance") {
              message("可用余额不足（需满足 balance - 冻结金额）", {
                type: "warning"
              });
              return;
            }
            if (errCode === "concurrent_wallet_update") {
              message("钱包并发更新冲突，请重试", { type: "warning" });
              return;
            }
            message(adminApiErrMessage(err, "保存失败"), { type: "error" });
          }
        });
      }
    });
  }

  const form = reactive({
    nickname: "",
    uid: "",
    phone: "",
    status: "" as string,
    /** '' | '1' 在线 | '0' 离线 */
    isOnline: "" as string
  });

  const loading = ref(true);
  const dataList = ref<any[]>([]);
  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 10,
    currentPage: 1,
    background: true
  });

  const columns: TableColumnList = [
    {
      label: "IM 号",
      prop: "id",
      minWidth: 118
    },
    {
      label: "头像",
      prop: "avatar",
      width: 80,
      cellRenderer: ({ row }) => (
        <el-image
          fit="cover"
          preview-teleported={true}
          src={row.avatar || userAvatar}
          preview-src-list={Array.of(row.avatar || userAvatar)}
          class="size-8 rounded-full align-middle"
        />
      )
    },
    {
      label: "昵称",
      prop: "nickname",
      minWidth: 120
    },
    {
      label: "手机号",
      prop: "phone",
      minWidth: 118
    },
    {
      label: "TRX地址",
      prop: "trxAddress",
      minWidth: 188,
      cellRenderer: ({ row }) => renderTrxAddress(row)
    },
    {
      label: "在线状态",
      prop: "online",
      width: 94,
      cellRenderer: ({ row, props }) => (
        <el-tag size={props.size} type={row.online ? "success" : "info"} effect="plain">
          {row.online ? "在线" : "离线"}
        </el-tag>
      )
    },
    {
      label: "好友数",
      prop: "friendCount",
      width: 76,
      formatter: ({ friendCount }) =>
        typeof friendCount === "number" ? String(friendCount) : "—"
    },
    {
      label: "有效群数",
      prop: "groupCount",
      width: 82,
      formatter: ({ groupCount }) =>
        typeof groupCount === "number" ? String(groupCount) : "—"
    },
    {
      label: "登录设备",
      prop: "loginDevices",
      minWidth: 150,
      showOverflowTooltip: true
    },
    {
      label: "机型",
      prop: "deviceModel",
      minWidth: 130,
      showOverflowTooltip: true,
      formatter: ({ deviceModel }) =>
        deviceModel == null || deviceModel === "" ? "—" : String(deviceModel)
    },
    {
      label: "登录 IP",
      prop: "loginIp",
      minWidth: 128
    },
    {
      label: "地址",
      prop: "locationCityLabel",
      minWidth: 140,
      showOverflowTooltip: true,
      formatter: ({ locationCityLabel }) =>
        locationCityLabel == null || locationCityLabel === ""
          ? "—"
          : String(locationCityLabel)
    },
    {
      label: "账户余额",
      prop: "walletBalances",
      width: 150,
      cellRenderer: ({ row }) => renderUserListWalletBalance(row)
    },
    {
      label: "个性签名",
      prop: "signature",
      minWidth: 140,
      showOverflowTooltip: true,
      /** 默认隐藏，可在表格右上角列设置中勾选显示 */
      hide: true
    },
    {
      label: "账号状态",
      prop: "status",
      minWidth: 96,
      cellRenderer: ({ row, props }) => {
        const raw = Number((row as { userStatusRaw?: unknown }).userStatusRaw);
        if (Number.isFinite(raw) && raw === -2) {
          return (
            <el-tag size={props.size} type="warning" effect="plain">
              注销
            </el-tag>
          );
        }
        const m =
          statusMap[row.status] ?? ({ label: "未知", type: "info" } as const);
        return (
          <el-tag size={props.size} type={m.type} effect="plain">
            {m.label}
          </el-tag>
        );
      }
    },
    {
      label: "最近登录",
      prop: "lastLoginTime",
      minWidth: 168,
      formatter: ({ lastLoginTime }) => {
        if (lastLoginTime == null || lastLoginTime === "") return "—";
        const d = dayjs(lastLoginTime as string | number);
        return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : "—";
      }
    },
    {
      label: "操作",
      fixed: "right",
      width: 180,
      slot: "operation"
    }
  ];

  async function onSearch() {
    loading.value = true;
    try {
      const keyword =
        [form.uid, form.nickname, form.phone]
          .map(s => String(s).trim())
          .filter(Boolean)
          .join(" ") || undefined;

      const res = await getAdminUsers({
        page: pagination.currentPage,
        page_size: pagination.pageSize,
        keyword,
        status: form.status === "" ? undefined : form.status,
        is_online: form.isOnline === "" ? undefined : form.isOnline,
        sort: "register_time_desc"
      });

      const avatarBase = import.meta.env.VITE_USER_AVATAR_BASE_URL ?? "";
      dataList.value = (res.items ?? []).map(item =>
        mapAdminUserItemToTableRow(item, String(avatarBase))
      );
      pagination.total = res.total ?? 0;
      if (
        typeof res.page_size === "number" &&
        res.page_size > 0 &&
        res.page_size !== pagination.pageSize
      ) {
        pagination.pageSize = res.page_size;
      }
    } catch (err: unknown) {
      dataList.value = [];
      pagination.total = 0;
      const ax = err as { response?: { status?: number; data?: { error?: string } } };
      const status = ax?.response?.status;
      const errCode = ax?.response?.data?.error;
      if (status === 403 || errCode === "forbidden") {
        message("无用户列表权限（需要 user.read）", { type: "warning" });
      } else if (status !== 401) {
        message(adminApiErrMessage(err, "用户列表加载失败"), { type: "warning" });
      }
    } finally {
      loading.value = false;
      tableRef.value?.setAdaptive?.();
    }
  }

  function resetForm(formEl?: any) {
    formEl?.resetFields?.();
    form.nickname = "";
    form.uid = "";
    form.phone = "";
    form.status = "";
    form.isOnline = "";
    pagination.currentPage = 1;
    onSearch();
  }

  function handleSizeChange(val: number) {
    pagination.pageSize = val;
    pagination.currentPage = 1;
    onSearch();
  }

  function handleCurrentChange(val: number) {
    pagination.currentPage = val;
    onSearch();
  }

  function handleViewDetail(row: any) {
    const id = normalizeImUserUid(row?.id ?? row?.uid);
    if (!isImUserUid(id)) {
      message("无法打开详情：缺少 IM 号", { type: "warning" });
      return;
    }
    router.push({
      name: "ImAccountUserDetail",
      params: { id }
    });
  }

  function handleViewPrivacy(row: any) {
    const id = normalizeImUserUid(row?.id ?? row?.uid);
    if (!isImUserUid(id)) {
      message("无法打开隐私详情：缺少 IM 号", { type: "warning" });
      return;
    }
    router.push({
      path: "/im-admin/privacy/contacts",
      query: { user_uid: id }
    });
  }

  useAdminRealtimeInvalidate(
    [
      ADMIN_REALTIME_EVENTS.USER_CREATED,
      ADMIN_REALTIME_EVENTS.USER_UPDATED,
      ADMIN_REALTIME_EVENTS.USER_STATUS_CHANGED,
      ADMIN_REALTIME_EVENTS.USER_ONLINE_CHANGED,
      ADMIN_REALTIME_EVENTS.DEVICE_ONLINE_CHANGED,
      ADMIN_REALTIME_EVENTS.USER_WALLET_CHANGED,
      ADMIN_REALTIME_EVENTS.WALLET_LEDGER_CREATED,
      ADMIN_REALTIME_EVENTS.WALLET_BALANCE_CHANGED,
      ADMIN_REALTIME_EVENTS.WALLET_BALANCE_UPDATED,
      ADMIN_REALTIME_EVENTS.WALLET_UPDATED,
      ADMIN_REALTIME_EVENTS.WALLET_ADJUSTED,
      ADMIN_REALTIME_EVENTS.FINANCE_UPDATED
    ],
    () => onSearch(),
    { debounceMs: 120 }
  );

  onMounted(() => {
    onSearch();
  });

  return {
    form,
    loading,
    columns,
    dataList,
    pagination,
    deviceDetection,
    onSearch,
    resetForm,
    handleSizeChange,
    handleCurrentChange,
    handleViewDetail,
    handleViewPrivacy,
    openAdjustBalance,
    openEditNickname,
    openResetLoginPassword,
    openResetTransactionPassword,
    confirmToggleLoginDisabled,
    openCreateUsersByCount,
    canUserWrite
  };
}
