import dayjs from "dayjs";
import { reactive, ref, onMounted, type Ref } from "vue";
import {
  getWalletSweepLogsApi,
  tronscanAddressUrl,
  tronscanTxUrl,
  type WalletSweepLogItem
} from "@/api/wallet-sweep-logs";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";
import type { PaginationProps } from "@pureadmin/table";

const statusTag: Record<string, "success" | "danger" | "info"> = {
  success: "success",
  failed: "danger"
};

const triggerLabel: Record<string, string> = {
  manual: "手动归集",
  collect_all: "批量归集",
  auto: "自动归集"
};

function fmtTime(raw?: string | null) {
  if (!raw) return "—";
  const d = dayjs(raw);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : raw;
}

export function useWalletSweepLogs(tableRef: Ref) {
  const form = reactive({
    keyword: "",
    status: "",
    trigger: "",
    createdFrom: "",
    createdTo: ""
  });

  const loading = ref(false);
  const dataList = ref<WalletSweepLogItem[]>([]);

  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 20,
    currentPage: 1,
    background: true
  });

  const columns: TableColumnList = [
    { label: "ID", prop: "id", width: 72 },
    { label: "用户 UID", prop: "user_uid", width: 120 },
    { label: "昵称", prop: "nickname", minWidth: 100 },
    {
      label: "充值地址",
      prop: "from_address",
      minWidth: 168,
      cellRenderer: ({ row }) => {
        const addr = row.from_address as string | undefined;
        if (!addr) return "—";
        const url = tronscanAddressUrl(addr);
        return url ? (
          <a href={url} target="_blank" rel="noopener noreferrer" class="text-primary">
            {addr}
          </a>
        ) : (
          addr
        );
      }
    },
    {
      label: "USDT 归集",
      prop: "usdt_swept",
      width: 110
    },
    {
      label: "TRX 归集",
      prop: "trx_swept",
      width: 110
    },
    {
      label: "USDT TxID",
      prop: "usdt_tx_id",
      minWidth: 140,
      showOverflowTooltip: true,
      cellRenderer: ({ row }) => {
        const tx = row.usdt_tx_id as string | undefined;
        if (!tx) return "—";
        const url = tronscanTxUrl(tx);
        return url ? (
          <a href={url} target="_blank" rel="noopener noreferrer" class="text-primary">
            {tx.slice(0, 10)}…
          </a>
        ) : (
          tx
        );
      }
    },
    {
      label: "TRX TxID",
      prop: "trx_tx_id",
      minWidth: 140,
      showOverflowTooltip: true,
      cellRenderer: ({ row }) => {
        const tx = row.trx_tx_id as string | undefined;
        if (!tx) return "—";
        const url = tronscanTxUrl(tx);
        return url ? (
          <a href={url} target="_blank" rel="noopener noreferrer" class="text-primary">
            {tx.slice(0, 10)}…
          </a>
        ) : (
          tx
        );
      }
    },
    {
      label: "状态",
      prop: "status",
      width: 88,
      cellRenderer: ({ row }) => {
        const s = `${row.status ?? ""}`.toLowerCase();
        const type = statusTag[s] ?? "info";
        const label = s === "success" ? "成功" : s === "failed" ? "失败" : s || "—";
        return <el-tag type={type}>{label}</el-tag>;
      }
    },
    {
      label: "触发方式",
      prop: "trigger_type",
      width: 108,
      formatter: ({ trigger_type }: WalletSweepLogItem) =>
        triggerLabel[`${trigger_type ?? ""}`.toLowerCase()] ?? trigger_type ?? "—"
    },
    { label: "操作人", prop: "operator", width: 100 },
    {
      label: "失败原因",
      prop: "fail_reason",
      minWidth: 160,
      showOverflowTooltip: true,
      formatter: ({ fail_reason }: WalletSweepLogItem) => fail_reason || "—"
    },
    {
      label: "时间",
      prop: "created_at",
      width: 168,
      formatter: ({ created_at }: WalletSweepLogItem) => fmtTime(created_at)
    }
  ];

  async function onSearch() {
    loading.value = true;
    try {
      const res = await getWalletSweepLogsApi({
        page: pagination.currentPage,
        page_size: pagination.pageSize,
        keyword: form.keyword || undefined,
        status: form.status || undefined,
        trigger: form.trigger || undefined,
        created_from: form.createdFrom || undefined,
        created_to: form.createdTo || undefined
      });
      dataList.value = res.items ?? [];
      pagination.total = res.total ?? 0;
    } catch (err) {
      message(adminApiErrMessage(err, "加载归集记录失败"), { type: "warning" });
    } finally {
      loading.value = false;
    }
  }

  function resetForm(formRef: Ref) {
    formRef.value?.resetFields?.();
    form.keyword = "";
    form.status = "";
    form.trigger = "";
    form.createdFrom = "";
    form.createdTo = "";
    pagination.currentPage = 1;
    onSearch();
  }

  function handleSizeChange(size: number) {
    pagination.pageSize = size;
    pagination.currentPage = 1;
    onSearch();
  }

  function handleCurrentChange(page: number) {
    pagination.currentPage = page;
    onSearch();
  }

  onMounted(() => {
    onSearch();
  });

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
