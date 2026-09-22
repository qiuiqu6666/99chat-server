import dayjs from "dayjs";
import { reactive, ref, onMounted, type Ref } from "vue";
import { getWalletTransfersApi, mapTransferListRow } from "@/api/im-finance";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";
import {
  formatWalletCurrencyAmount,
  formatWalletCurrencyLabel
} from "./walletCurrencyPolicy";
import type { PaginationProps } from "@pureadmin/table";

const statusTag: Record<
  string,
  "success" | "warning" | "danger" | "info"
> = {
  成功: "success",
  待审核: "warning",
  失败: "danger",
  已取消: "info",
  处理中: "warning"
};

export function useFinanceTransferLogs(tableRef: Ref) {
  const form = reactive({
    uid: "",
    keyword: "",
    status: "",
    transactionType: "" as string
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
      label: "交易流水号",
      prop: "tradeNo",
      minWidth: 186,
      showOverflowTooltip: true
    },
    { label: "付款 IM 号", prop: "fromUid", width: 128 },
    { label: "付款方", prop: "fromNickname", minWidth: 100 },
    { label: "收款 IM 号", prop: "toUid", width: 128 },
    { label: "收款方", prop: "toNickname", minWidth: 100 },
    {
      label: "币种",
      prop: "currency",
      width: 88,
      formatter: ({ currency }: { currency?: string }) =>
        formatWalletCurrencyLabel(currency)
    },
    {
      label: "金额",
      prop: "amount",
      width: 132,
      formatter: ({
        amount,
        currency
      }: {
        amount?: string;
        currency?: string;
      }) => formatWalletCurrencyAmount(currency, amount)
    },
    {
      label: "手续费",
      prop: "fee",
      width: 112,
      formatter: ({
        fee,
        currency
      }: {
        fee?: string;
        currency?: string;
      }) => formatWalletCurrencyAmount(currency, fee)
    },
    {
      label: "状态",
      prop: "status",
      width: 96,
      cellRenderer: ({ row, props }) => (
        <el-tag
          size={props.size}
          type={statusTag[row.status as string] ?? "info"}
          effect="plain"
        >
          {row.status}
        </el-tag>
      )
    },
    {
      label: "附言",
      prop: "memo",
      minWidth: 140,
      showOverflowTooltip: true
    },
    {
      label: "时间",
      prop: "createdAt",
      minWidth: 172,
      formatter: ({ createdAt }: { createdAt?: number }) =>
        Number.isFinite(createdAt) && (createdAt as number) > 0
          ? dayjs(createdAt).format("YYYY-MM-DD HH:mm:ss")
          : "—"
    }
  ];

  async function onSearch() {
    loading.value = true;
    try {
      const u = form.uid.trim();
      const params: Record<string, unknown> = {
        page: pagination.currentPage,
        page_size: pagination.pageSize,
        sort: "create_time_desc"
      };
      if (u) params.user_uid = u;
      if (form.transactionType === "3" || form.transactionType === "4")
        params.transaction_type = form.transactionType;
      if (form.status !== "") params.status = form.status;
      const kw = form.keyword.trim();
      if (kw) params.keyword = kw;

      const res = await getWalletTransfersApi(params);
      const items = res.items;
      const list = Array.isArray(items)
        ? items.map(r => mapTransferListRow(r as Record<string, unknown>))
        : [];
      dataList.value = list;
      pagination.total = typeof res.total === "number" ? res.total : list.length;
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
      if (
        ax?.response?.status === 403 ||
        ax?.response?.data?.error === "forbidden"
      ) {
        message("无权限（需要 wallet.read）", { type: "warning" });
      } else if (ax?.response?.status !== 401) {
        message(adminApiErrMessage(err, "转账流水加载失败"), {
          type: "warning"
        });
      }
    } finally {
      loading.value = false;
      tableRef.value?.setAdaptive?.();
    }
  }

  function resetForm(formEl?: any) {
    formEl?.resetFields?.();
    form.uid = "";
    form.keyword = "";
    form.status = "";
    form.transactionType = "";
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
      ADMIN_REALTIME_EVENTS.TRANSFER_UPDATED,
      ADMIN_REALTIME_EVENTS.WALLET_LEDGER_CREATED,
      ADMIN_REALTIME_EVENTS.FINANCE_UPDATED
    ],
    () => onSearch(),
    { debounceMs: 180 }
  );

  onMounted(onSearch);

  return {
    form,
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
