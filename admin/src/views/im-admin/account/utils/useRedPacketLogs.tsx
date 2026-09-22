import dayjs from "dayjs";
import { reactive, ref, onMounted, type Ref } from "vue";
import {
  getRedPacketsApi,
  mapRedPacketListRow
} from "@/api/im-finance";
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
  进行中: "warning",
  已领完: "success",
  已过期: "danger"
};

export function useRedPacketLogs(tableRef: Ref) {
  const form = reactive({
    uid: "",
    keyword: "",
    packetType: "",
    status: ""
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
      label: "单号",
      prop: "orderNo",
      minWidth: 168,
      showOverflowTooltip: true
    },
    { label: "发红包用户", prop: "uid", width: 128 },
    { label: "昵称", prop: "nickname", minWidth: 110 },
    { label: "类型", prop: "packetType", width: 100 },
    {
      label: "币种",
      prop: "currency",
      width: 88,
      formatter: ({ currency }: { currency?: string }) =>
        formatWalletCurrencyLabel(currency)
    },
    {
      label: "对象摘要",
      prop: "targetSummary",
      minWidth: 112,
      showOverflowTooltip: true
    },
    {
      label: "总金额",
      prop: "totalAmount",
      width: 132,
      formatter: ({
        totalAmount,
        currency
      }: {
        totalAmount?: string;
        currency?: string;
      }) => formatWalletCurrencyAmount(currency, totalAmount)
    },
    {
      label: "领取进度",
      prop: "grabbedCount",
      width: 112,
      formatter: ({
        grabbedCount,
        packetCount
      }: {
        grabbedCount?: number;
        packetCount?: number;
      }) => `${grabbedCount ?? 0} / ${packetCount ?? 0}`
    },
    {
      label: "状态",
      prop: "status",
      width: 104,
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
      if (u) params.involves_user_uid = u;
      if (form.keyword.trim()) params.keyword = form.keyword.trim();

      if (form.status === "0" || form.status === "1" || form.status === "2")
        params.status = form.status;

      const res = await getRedPacketsApi(params);
      const items = res.items;
      let list = Array.isArray(items)
        ? items.map(r => mapRedPacketListRow(r as Record<string, unknown>))
        : [];
      if (form.packetType) {
        list = list.filter(row => row.packetType === form.packetType);
      }
      dataList.value = list;
      pagination.total =
        form.packetType && typeof res.total === "number"
          ? list.length
          : typeof res.total === "number"
            ? res.total
            : list.length;
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
        message(adminApiErrMessage(err, "红包列表加载失败"), {
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
    form.packetType = "";
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
      ADMIN_REALTIME_EVENTS.RED_PACKET_UPDATED,
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
