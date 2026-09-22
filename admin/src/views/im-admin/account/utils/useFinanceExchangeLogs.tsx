import dayjs from "dayjs";
import { reactive, ref, onMounted, type Ref } from "vue";
import { getWalletExchangesApi, mapExchangeListRow } from "@/api/im-finance";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";
import {
  formatWalletCurrencyAmount,
  formatWalletCurrencyLabel
} from "./walletCurrencyPolicy";
import type { PaginationProps } from "@pureadmin/table";

const statusTag: Record<string, "success" | "warning" | "danger" | "info"> = {
  成功: "success",
  失败: "danger"
};

export function useFinanceExchangeLogs(tableRef: Ref) {
  const form = reactive({
    uid: "",
    keyword: "",
    direction: ""
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
      label: "闪兑单号",
      prop: "orderNo",
      minWidth: 168,
      showOverflowTooltip: true
    },
    { label: "用户 IM 号", prop: "uid", width: 128 },
    { label: "昵称", prop: "nickname", minWidth: 100 },
    { label: "方向", prop: "directionLabel", width: 132 },
    {
      label: "支付",
      prop: "inputAmount",
      width: 132,
      formatter: ({
        inputAmount,
        inputCurrency
      }: {
        inputAmount?: string;
        inputCurrency?: string;
      }) => formatWalletCurrencyAmount(inputCurrency, inputAmount)
    },
    {
      label: "支付币种",
      prop: "inputCurrency",
      width: 88,
      formatter: ({ inputCurrency }: { inputCurrency?: string }) =>
        formatWalletCurrencyLabel(inputCurrency)
    },
    {
      label: "获得",
      prop: "outputAmount",
      width: 132,
      formatter: ({
        outputAmount,
        outputCurrency
      }: {
        outputAmount?: string;
        outputCurrency?: string;
      }) => formatWalletCurrencyAmount(outputCurrency, outputAmount)
    },
    {
      label: "获得币种",
      prop: "outputCurrency",
      width: 88,
      formatter: ({ outputCurrency }: { outputCurrency?: string }) =>
        formatWalletCurrencyLabel(outputCurrency)
    },
    {
      label: "汇率",
      prop: "rate",
      width: 96,
      showOverflowTooltip: true
    },
    {
      label: "状态",
      prop: "status",
      width: 88,
      cellRenderer: ({ row, props }) => (
        <el-tag
          size={props.size}
          type={statusTag[row.status as string] ?? "success"}
          effect="plain"
        >
          {row.status}
        </el-tag>
      )
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
      if (form.direction) params.direction = form.direction;
      if (form.keyword.trim()) params.keyword = form.keyword.trim();

      const res = await getWalletExchangesApi(params);
      const items = res.items;
      const list = Array.isArray(items)
        ? items.map(r => mapExchangeListRow(r as Record<string, unknown>))
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
        message(adminApiErrMessage(err, "闪兑记录加载失败"), {
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
    form.direction = "";
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
