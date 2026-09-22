import dayjs from "dayjs";
import { reactive, ref, onMounted, type Ref } from "vue";
import {
  getWalletLedgerApi,
  mapTransferListRow,
  pickStr
} from "@/api/im-finance";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";
import type { PaginationProps } from "@pureadmin/table";

function fmtYuan(n: unknown) {
  const x = Number(String(n).replace(",", ""));
  if (!Number.isFinite(x)) return "¥0.00";
  return `¥${x.toLocaleString("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })}`;
}

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

export function useWalletLedger(tableRef: Ref) {
  const form = reactive({
    uid: "",
    keyword: "",
    /** 可多选 transaction_type：1～7 */
    transactionTypes: [] as string[],
    status: ""
  });

  const typeLabels = ref<Record<string, string>>({});

  const loading = ref(false);
  const dataList = ref<any[]>([]);

  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 20,
    currentPage: 1,
    background: true
  });

  const columns: TableColumnList = [
    {
      label: "币种",
      prop: "currency",
      width: 90
    },
    {
      label: "账变类型",
      prop: "transactionTypeNum",
      minWidth: 160,
      formatter: ({
        transactionTypeNum
      }: {
        transactionTypeNum?: number | null;
      }) => {
        if (
          transactionTypeNum == null ||
          !Number.isFinite(Number(transactionTypeNum))
        ) {
          return "—";
        }
        const n = Number(transactionTypeNum);
        const key = String(n);
        const en = typeLabels.value[key];
        return en ? `${n} (${en})` : String(n);
      }
    },
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
      label: "金额",
      prop: "amount",
      width: 120,
      formatter: ({ amount }: { amount?: string }) => fmtYuan(amount)
    },
    {
      label: "手续费",
      prop: "fee",
      width: 100,
      formatter: ({ fee }: { fee?: string }) => fmtYuan(fee)
    },
    {
      label: "变动前",
      prop: "balanceBefore",
      width: 120,
      formatter: ({ balanceBefore }: { balanceBefore?: string }) =>
        balanceBefore == null ? "—" : fmtYuan(balanceBefore)
    },
    {
      label: "变动后",
      prop: "balanceAfter",
      width: 120,
      formatter: ({ balanceAfter }: { balanceAfter?: string }) =>
        balanceAfter == null ? "—" : fmtYuan(balanceAfter)
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
    const u = form.uid.trim();

    loading.value = true;
    try {
      const res = await getWalletLedgerApi({
        ...(u ? { user_uid: u } : {}),
        page: pagination.currentPage,
        page_size: pagination.pageSize,
        sort: "create_time_desc",
        ...(form.transactionTypes.length > 0
          ? { transaction_type: [...form.transactionTypes].sort().join(",") }
          : {}),
        ...(form.status !== "" ? { status: form.status } : {})
      });

      const tt = res.transaction_types;
      if (tt != null && typeof tt === "object" && !Array.isArray(tt)) {
        typeLabels.value = tt as Record<string, string>;
      } else {
        typeLabels.value = {};
      }

      const items = res.items;
      const rawList = Array.isArray(items) ? items : [];
      const baseRows = rawList.map((r, idx) => {
        const row = mapTransferListRow(r as Record<string, unknown>);
        const raw = row._raw as Record<string, unknown>;
        const id = pickStr(raw, ["id", "transaction_no"]);
        const ledgerRowKey =
          id !== ""
            ? `${id}-${idx}`
            : `${row.tradeNo}-${String(row.createdAt)}-${idx}`;
        return { ...row, ledgerRowKey };
      });

      const kw = form.keyword.trim();
      dataList.value = kw
        ? baseRows.filter(
            row =>
              `${row.tradeNo}`.includes(kw) ||
              `${row.memo}`.includes(kw) ||
              `${row.fromUid}`.includes(kw) ||
              `${row.toUid}`.includes(kw)
          )
        : baseRows;

      pagination.total = typeof res.total === "number" ? res.total : baseRows.length;
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
      typeLabels.value = {};
      message(adminApiErrMessage(err, "账变记录加载失败"), {
        type: "warning"
      });
    } finally {
      loading.value = false;
      tableRef.value?.setAdaptive?.();
    }
  }

  useAdminRealtimeInvalidate(
    [
      ADMIN_REALTIME_EVENTS.WALLET_LEDGER_CREATED,
      ADMIN_REALTIME_EVENTS.WALLET_BALANCE_CHANGED,
      ADMIN_REALTIME_EVENTS.WALLET_BALANCE_UPDATED,
      ADMIN_REALTIME_EVENTS.WALLET_UPDATED,
      ADMIN_REALTIME_EVENTS.WALLET_ADJUSTED,
      ADMIN_REALTIME_EVENTS.FINANCE_UPDATED
    ],
    () => onSearch(),
    { debounceMs: 180 }
  );

  onMounted(onSearch);

  function resetForm(formEl?: any) {
    formEl?.resetFields?.();
    form.uid = "";
    form.keyword = "";
    form.transactionTypes = [];
    form.status = "";
    pagination.currentPage = 1;
    dataList.value = [];
    pagination.total = 0;
    typeLabels.value = {};
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

  return {
    form,
    loading,
    columns,
    dataList,
    pagination,
    typeLabels,
    onSearch,
    resetForm,
    handleSizeChange,
    handleCurrentChange
  };
}
