import { reactive, ref, onMounted, type Ref } from "vue";
import {
  getAdminAuditLogsApi,
  type AdminAuditLogsParams
} from "@/api/im-admin-logs";
import { adminApiErrMessage } from "@/api/im-user";
import { displayIpRegion, resolveIpRegions } from "@/utils/ipRegion";
import { message } from "@/utils/message";
import type { PaginationProps } from "@pureadmin/table";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import { hasPerms } from "@/utils/auth";

function fmtDetailPreview(detail: unknown): string {
  if (detail == null) return "—";
  try {
    const s = typeof detail === "string" ? detail : JSON.stringify(detail);
    return s.length > 400 ? `${s.slice(0, 400)}…` : s;
  } catch {
    return String(detail);
  }
}

export function useAdminAuditLogs(tableRef: Ref) {
  const form = reactive({
    adminUserId: "",
    action: "",
    resourceType: "",
    resourceId: "",
    createdFrom: "",
    createdTo: ""
  });

  const loading = ref(true);
  const dataSource = ref("");
  const dataList = ref<Record<string, unknown>[]>([]);
  const regionByIp = ref<Record<string, string>>({});
  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 50,
    currentPage: 1,
    background: true
  });

  const columns: TableColumnList = [
    { label: "id", prop: "id", width: 82, showOverflowTooltip: true },
    {
      label: "操作者 admin_user_id",
      prop: "admin_user_id",
      width: 120,
      showOverflowTooltip: true
    },
    {
      label: "action",
      prop: "action",
      minWidth: 200,
      showOverflowTooltip: true
    },
    {
      label: "resource_type",
      prop: "resource_type",
      width: 132,
      showOverflowTooltip: true
    },
    {
      label: "resource_id",
      prop: "resource_id",
      minWidth: 120,
      showOverflowTooltip: true
    },
    {
      label: "detail",
      prop: "detail",
      minWidth: 220,
      showOverflowTooltip: true,
      formatter: ({ detail }: Record<string, unknown>) =>
        fmtDetailPreview(detail)
    },
    { label: "IP", prop: "ip", width: 138, showOverflowTooltip: true },
    {
      label: "地区",
      prop: "geo_address",
      minWidth: 100,
      showOverflowTooltip: true,
      formatter: (row: Record<string, unknown>) =>
        displayIpRegion(row.ip, regionByIp.value, row.geo_address)
    },
    {
      label: "时间",
      prop: "created_at",
      minWidth: 172,
      formatter: ({ created_at }: Record<string, unknown>) =>
        formatAdminUnixTime(created_at ?? null, true)
    },
    {
      label: "管理员账号",
      prop: "admin_username",
      width: 120,
      showOverflowTooltip: true
    },
    {
      label: "显示名",
      prop: "admin_display_name",
      minWidth: 110,
      showOverflowTooltip: true
    }
  ];

  async function onSearch() {
    loading.value = true;
    try {
      const params: AdminAuditLogsParams = {
        page: pagination.currentPage,
        page_size: pagination.pageSize,
        sort: "created_at_desc"
      };
      const aid = form.adminUserId.trim();
      if (aid) params.admin_user_id = aid.slice(0, 64);
      const ac = form.action.trim();
      if (ac) params.action = ac.slice(0, 128);
      const rt = form.resourceType.trim();
      if (rt) params.resource_type = rt.slice(0, 64);
      const rid = form.resourceId.trim();
      if (rid) params.resource_id = rid.slice(0, 128);
      const fr = form.createdFrom.trim();
      const to = form.createdTo.trim();
      if (fr) params.created_from = fr;
      if (to) params.created_to = to;

      const res = await getAdminAuditLogsApi(params);
      dataSource.value = typeof res.source === "string" ? res.source : "";
      dataList.value = (res.items ?? []) as Record<string, unknown>[];
      regionByIp.value = await resolveIpRegions(
        dataList.value.map(row => row.ip as string | null | undefined)
      );
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
      const ax = err as {
        response?: { status?: number; data?: { error?: string } };
      };
      if (
        ax?.response?.status === 403 ||
        ax?.response?.data?.error === "forbidden"
      ) {
        message("无权限（需要 admin.manage）", { type: "warning" });
      } else if (ax?.response?.status === 422) {
        message(adminApiErrMessage(err, "日期或其它参数格式错误"), {
          type: "warning"
        });
      } else if (ax?.response?.status !== 401) {
        message(adminApiErrMessage(err, "操作审计加载失败"), {
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
    form.adminUserId = "";
    form.action = "";
    form.resourceType = "";
    form.resourceId = "";
    form.createdFrom = "";
    form.createdTo = "";
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

  onMounted(() => {
    if (!hasPerms("admin.manage")) {
      loading.value = false;
      dataList.value = [];
      pagination.total = 0;
      return;
    }
    onSearch();
  });

  return {
    form,
    dataSource,
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
