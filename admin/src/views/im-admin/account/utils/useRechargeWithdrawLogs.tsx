import dayjs from "dayjs";
import { computed, reactive, ref, onMounted, type Ref } from "vue";
import { useRoute } from "vue-router";
import { ElMessageBox } from "element-plus";
import {
  getRechargeWithdrawLogsApi,
  mapRechargeWithdrawRow,
  tronscanTransactionUrl
} from "@/api/im-finance";
import {
  adminWithdrawApproveApi,
  adminWithdrawRejectApi
} from "@/api/im-finance-actions";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";
import type { PaginationProps } from "@pureadmin/table";

function fmtYuan(n: unknown) {
  const x = Number(n);
  if (!Number.isFinite(x)) return "¥0.00";
  return `¥${x.toLocaleString("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })}`;
}

const statusTag: Record<string, "success" | "warning" | "danger" | "info"> = {
  成功: "success",
  已通过: "success",
  处理中: "warning",
  失败: "danger",
  已拒绝: "danger",
  已取消: "info",
  待审核: "warning"
};

const bizTag: Record<string, "success" | "warning"> = {
  充值: "success",
  提现: "warning"
};

export function useRechargeWithdrawLogs(
  tableRef: Ref,
  totpSetupRef?: Ref<{ promptSetupIfNeeded?: () => Promise<boolean>; isTotpConfigured?: () => boolean } | null>
) {
  const route = useRoute();
  const isWithdrawAudit = computed(() => String(route.path || "").includes("/withdraws"));
  const routeBizType = computed(() => {
    const p = String(route.path || "");
    if (p.includes("/recharges")) return "充值";
    if (p.includes("/withdraws")) return "提现";
    return "";
  });
  const pageTitle = computed(() => {
    if (isWithdrawAudit.value) return "提现审核";
    if (routeBizType.value) return `${routeBizType.value}记录`;
    return "充值提现记录";
  });

  const form = reactive({
    uid: "",
    keyword: "",
    bizType: routeBizType.value,
    status: ""
  });

  const actionLoading = ref("");
  const loading = ref(true);
  const dataList = ref<any[]>([]);
  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 20,
    currentPage: 1,
    background: true
  });

  async function promptTotpCode(actionLabel: string) {
    try {
      const { value } = await ElMessageBox.prompt(
        `请输入 Google Authenticator 中的 6 位验证码以${actionLabel}：`,
        "谷歌验证",
        {
          type: "warning",
          confirmButtonText: "确认",
          cancelButtonText: "取消",
          inputPlaceholder: "6 位数字",
          inputPattern: /^\d{6}$/,
          inputErrorMessage: "请输入 6 位数字验证码"
        }
      );
      return value?.trim() ?? "";
    } catch {
      return "";
    }
  }

  async function ensureTotpReady() {
    if (!isWithdrawAudit.value) return true;
    const setup = totpSetupRef?.value;
    if (setup?.isTotpConfigured?.()) return true;
    if (setup?.promptSetupIfNeeded) {
      return setup.promptSetupIfNeeded();
    }
    return true;
  }

  async function handleApprove(row: { bizNo?: string; uid?: string; amount?: number }) {
    const orderNo = String(row.bizNo ?? "").trim();
    if (!orderNo) {
      message("缺少业务单号", { type: "warning" });
      return;
    }
    if (!(await ensureTotpReady())) return;
    try {
      await ElMessageBox.confirm(
        `确认通过 UID ${row.uid ?? "—"} 的提现申请？金额 ${fmtYuan(row.amount)}`,
        "提现审核",
        { type: "warning", confirmButtonText: "下一步", cancelButtonText: "取消" }
      );
    } catch {
      return;
    }
    const totpCode = await promptTotpCode("通过审核");
    if (!totpCode) return;
    actionLoading.value = orderNo;
    try {
      await adminWithdrawApproveApi(orderNo, { totp_code: totpCode });
      message("已通过，正在链上打款", { type: "success" });
      await onSearch();
    } catch (err: unknown) {
      message(adminApiErrMessage(err, "审核通过失败"), { type: "warning" });
    } finally {
      actionLoading.value = "";
    }
  }

  async function handleReject(row: { bizNo?: string; uid?: string; amount?: number }) {
    const orderNo = String(row.bizNo ?? "").trim();
    if (!orderNo) {
      message("缺少业务单号", { type: "warning" });
      return;
    }
    if (!(await ensureTotpReady())) return;
    let remark = "";
    try {
      const { value } = await ElMessageBox.prompt(
        `拒绝 UID ${row.uid ?? "—"} 的提现申请（${fmtYuan(row.amount)}），可填写原因：`,
        "拒绝提现",
        {
          type: "warning",
          confirmButtonText: "下一步",
          cancelButtonText: "取消",
          inputPlaceholder: "可选，如：地址异常"
        }
      );
      remark = value?.trim() ?? "";
    } catch {
      return;
    }
    const totpCode = await promptTotpCode("拒绝审核");
    if (!totpCode) return;
    actionLoading.value = orderNo;
    try {
      await adminWithdrawRejectApi(orderNo, { remark, totp_code: totpCode });
      message("已拒绝并退回余额", { type: "success" });
      await onSearch();
    } catch (err: unknown) {
      message(adminApiErrMessage(err, "拒绝失败"), { type: "warning" });
    } finally {
      actionLoading.value = "";
    }
  }

  const columns = computed<TableColumnList>(() => {
    const base: TableColumnList = [
      { label: "业务单号", prop: "bizNo", minWidth: 170, showOverflowTooltip: true },
      { label: "IM 号", prop: "uid", width: 128 },
      { label: "昵称", prop: "nickname", minWidth: 106 },
      {
        label: "类型",
        prop: "bizType",
        width: 96,
        cellRenderer: ({ row, props }) => (
          <el-tag size={props.size} type={bizTag[row.bizType as string] ?? "info"} effect="plain">
            {row.bizType}
          </el-tag>
        )
      },
      { label: "渠道/链", prop: "channel", width: 106 },
      { label: "地址", prop: "address", minWidth: 180, showOverflowTooltip: true },
      {
        label: "金额",
        prop: "amount",
        width: 120,
        formatter: ({ amount }: { amount?: number }) => fmtYuan(amount)
      },
      {
        label: "状态",
        prop: "status",
        width: 96,
        cellRenderer: ({ row, props }) => (
          <el-tag size={props.size} type={statusTag[row.status as string] ?? "info"} effect="plain">
            {row.status}
          </el-tag>
        )
      },
      {
        label: "第三方流水号",
        prop: "externalOrderNo",
        minWidth: 168,
        showOverflowTooltip: true,
        cellRenderer: ({ row }) => {
          const txid = String(row.externalOrderNo ?? "").trim();
          if (!txid) return "—";
          const url = tronscanTransactionUrl(txid);
          if (!url) return txid;
          return (
            <a
              href={url}
              target="_blank"
              rel="noopener noreferrer"
              class="text-[var(--el-color-primary)] hover:underline"
              title="在 Tronscan 查看链上详情"
            >
              {txid}
            </a>
          );
        }
      },
      {
        label: "时间",
        prop: "createdAt",
        minWidth: 172,
        formatter: ({ createdAt }: { createdAt?: number }) =>
          createdAt ? dayjs(createdAt).format("YYYY-MM-DD HH:mm:ss") : "—"
      }
    ];
    if (isWithdrawAudit.value) {
      base.push({
        label: "操作",
        prop: "actions",
        fixed: "right",
        width: 168,
        cellRenderer: ({ row }) => {
          if (row.status !== "待审核") return <span class="text-gray-400">—</span>;
          const busy = actionLoading.value === row.bizNo;
          return (
            <div class="flex items-center justify-center gap-1">
              <el-button
                link
                type="success"
                loading={busy}
                disabled={busy}
                onClick={() => handleApprove(row)}
              >
                通过
              </el-button>
              <el-button
                link
                type="danger"
                loading={busy}
                disabled={busy}
                onClick={() => handleReject(row)}
              >
                拒绝
              </el-button>
            </div>
          );
        }
      });
    }
    return base;
  });

  async function onSearch() {
    loading.value = true;
    try {
      const enforcedBizType = routeBizType.value;
      if (enforcedBizType) form.bizType = enforcedBizType;
      const uid = form.uid.trim() || undefined;
      const res = (await getRechargeWithdrawLogsApi({
        page: pagination.currentPage,
        page_size: pagination.pageSize,
        user_uid: uid,
        keyword: form.keyword.trim() || undefined,
        status: form.status || undefined,
        biz_type: form.bizType || undefined
      })) as Record<string, unknown>;

      let rows = ((res.items as Record<string, unknown>[]) ?? []).map(mapRechargeWithdrawRow);

      dataList.value = rows;
      pagination.total = typeof res.total === "number" ? res.total : rows.length;
      if (typeof res.page_size === "number" && res.page_size > 0) {
        pagination.pageSize = res.page_size;
      }
    } catch (err: unknown) {
      dataList.value = [];
      pagination.total = 0;
      message(adminApiErrMessage(err, `${pageTitle.value}加载失败`), { type: "warning" });
    } finally {
      loading.value = false;
      tableRef.value?.setAdaptive?.();
    }
  }

  function resetForm(formEl?: any) {
    formEl?.resetFields?.();
    form.uid = "";
    form.keyword = "";
    form.bizType = routeBizType.value;
    form.status = "";
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

  useAdminRealtimeInvalidate(
    [
      ADMIN_REALTIME_EVENTS.RECHARGE_STATUS_CHANGED,
      ADMIN_REALTIME_EVENTS.WITHDRAW_STATUS_CHANGED,
      ADMIN_REALTIME_EVENTS.WALLET_LEDGER_CREATED,
      ADMIN_REALTIME_EVENTS.FINANCE_UPDATED
    ],
    () => onSearch(),
    { debounceMs: 180 }
  );

  onMounted(onSearch);

  return {
    form,
    pageTitle,
    isWithdrawAudit,
    loading,
    columns,
    dataList,
    pagination,
    onSearch,
    resetForm,
    handleSizeChange,
    handleCurrentChange
  };
}
