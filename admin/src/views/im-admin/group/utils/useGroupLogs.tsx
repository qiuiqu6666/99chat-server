import dayjs from "dayjs";
import { reactive, ref, type Ref } from "vue";
import {
  getGroupsOperationLogsApi,
  getGroupsOperationLogsAllApi
} from "@/api/im-group";
import { adminApiErrMessage } from "@/api/im-user";
import {
  formatGroupLogSummary,
  formatGroupOperationDetail
} from "@/utils/groupOperationLogDisplay";
import { message } from "@/utils/message";
import type { PaginationProps } from "@pureadmin/table";

const GID_RE = /^[A-Za-z0-9@#_-]{1,128}$/;

const KIND_LABEL: Record<string, string> = {
  im_system: "IM 系统提示",
  im_system_message: "IM 系统提示",
  join_request: "入群申请",
  member_mute: "成员禁言"
};

function kindLabel(kind: string) {
  if (!kind) return "—";
  return KIND_LABEL[kind] ?? kind;
}

export function useGroupLogs(tableRef: Ref) {
  const form = reactive({
    /** 留空：全站 `operation-logs-all`；填写：单群 `operation-logs` */
    gId: "",
    kinds: [] as string[]
  });

  const timelineMeta = ref<Record<string, unknown>>({});

  const loading = ref(false);
  const dataList = ref<
    Array<{
      rowKey: string;
      gIdDisplay: string;
      gNameDisplay: string;
      groupModeDisplay: string;
      kind: string;
      kindDisplay: string;
      occurredAtMs: number;
      summary: string;
      detailPreview: string;
    }>
  >([]);

  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 30,
    currentPage: 1,
    background: true
  });

  const columns: TableColumnList = [
    {
      label: "群 ID",
      prop: "gIdDisplay",
      width: 124,
      showOverflowTooltip: true
    },
    {
      label: "群名称",
      prop: "gNameDisplay",
      minWidth: 140,
      showOverflowTooltip: true
    },
    {
      label: "group_mode",
      prop: "groupModeDisplay",
      width: 104,
      showOverflowTooltip: true
    },
    { label: "类型", prop: "kindDisplay", width: 120 },
    {
      label: "时间",
      prop: "occurredAtMs",
      minWidth: 174,
      formatter: ({
        occurredAtMs
      }: {
        occurredAtMs?: number;
      }) =>
        Number.isFinite(occurredAtMs) && (occurredAtMs as number) > 0
          ? dayjs(occurredAtMs).format("YYYY-MM-DD HH:mm:ss.SSS")
          : "—"
    },
    {
      label: "摘要",
      prop: "summary",
      minWidth: 200,
      showOverflowTooltip: true
    },
    {
      label: "详情",
      prop: "detailPreview",
      minWidth: 280,
      showOverflowTooltip: true
    }
  ];

  async function onSearch() {
    const gid = form.gId.trim();
    if (gid !== "" && !GID_RE.test(gid)) {
      message("群 ID（g_id）须为 1～32 位字母或数字；留空则查询全站", {
        type: "warning"
      });
      return;
    }

    loading.value = true;
    try {
      const kindsParam =
        form.kinds.length > 0 ? form.kinds.join(",") : undefined;

      const res = (gid === ""
        ? await getGroupsOperationLogsAllApi({
            page: pagination.currentPage,
            page_size: pagination.pageSize,
            kinds: kindsParam
          })
        : await getGroupsOperationLogsApi({
            g_id: gid,
            page: pagination.currentPage,
            page_size: pagination.pageSize,
            kinds: kindsParam
          })) as Record<string, unknown>;

      timelineMeta.value = {
        scope: res.scope,
        g_id: res.g_id,
        g_name: res.g_name,
        group_mode: res.group_mode,
        source: res.source,
        kinds: res.kinds
      };

      const rawItems = (res.items as Record<string, unknown>[]) ?? [];
      dataList.value = rawItems.map((row, idx) => {
        const kind = String(row.kind ?? "");
        const atRaw = row.occurred_at_ms;
        const atNum =
          typeof atRaw === "number"
            ? atRaw
            : typeof atRaw === "string"
              ? Number(atRaw)
              : Number.NaN;
        const ms = Number.isFinite(atNum) ? atNum : Number.NaN;

        const gidRow = row.g_id != null ? String(row.g_id) : "";
        const gnameRow = row.g_name != null ? String(row.g_name) : "";
        const modeRow =
          row.group_mode != null && `${row.group_mode}` !== ""
            ? String(row.group_mode)
            : "";

        const gidDisp =
          gidRow ||
          (res.g_id != null ? String(res.g_id) : "") ||
          (gid ? gid : "");
        const gnameDisp =
          gnameRow ||
          (res.g_name != null ? String(res.g_name) : "") ||
          "—";
        const modeDisp =
          modeRow ||
          (res.group_mode != null && `${res.group_mode}` !== ""
            ? String(res.group_mode)
            : "—");

        return {
          rowKey: `${gidDisp || "na"}-${kind}-${String(atRaw ?? "")}-${idx}`,
          gIdDisplay: gidDisp || "—",
          gNameDisplay: gnameDisp,
          groupModeDisplay: modeDisp,
          kind,
          kindDisplay: kindLabel(kind),
          occurredAtMs: Number.isFinite(ms) ? ms : Number.NaN,
          summary: formatGroupLogSummary(kind, row.summary, row.detail),
          detailPreview: formatGroupOperationDetail(kind, row.detail)
        };
      });
      pagination.total = typeof res.total === "number" ? res.total : 0;
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
      timelineMeta.value = {};
      message(adminApiErrMessage(err, "群组日志加载失败"), {
        type: "warning"
      });
    } finally {
      loading.value = false;
      tableRef.value?.setAdaptive?.();
    }
  }

  function resetForm(formEl?: any) {
    formEl?.resetFields?.();
    form.gId = "";
    form.kinds = [];
    pagination.currentPage = 1;
    timelineMeta.value = {};
    void onSearch();
  }

  function handleSizeChange(val: number) {
    pagination.pageSize = val;
    pagination.currentPage = 1;
    void onSearch();
  }

  function handleCurrentChange(val: number) {
    pagination.currentPage = val;
    void onSearch();
  }

  return {
    form,
    loading,
    columns,
    dataList,
    pagination,
    timelineMeta,
    onSearch,
    resetForm,
    handleSizeChange,
    handleCurrentChange
  };
}
