import { reactive, ref, onMounted, type Ref } from "vue";
import {
  getAdminLoginLogsApi,
  type AdminLoginLogsParams
} from "@/api/im-admin-logs";
import { adminApiErrMessage } from "@/api/im-user";
import { displayIpRegion, resolveIpRegions } from "@/utils/ipRegion";
import { message } from "@/utils/message";
import type { PaginationProps } from "@pureadmin/table";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import { hasPerms } from "@/utils/auth";

export function useAdminLoginLogs(tableRef: Ref) {
  const form = reactive({
    adminUserId: "",
    username: "",
    success: "" as string,
    ip: "",
    loginAtFrom: "",
    loginAtTo: ""
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
    { label: "id", prop: "id", width: 80, showOverflowTooltip: true },
    {
      label: "admin_user_id",
      prop: "admin_user_id",
      width: 116,
      showOverflowTooltip: true
    },
    {
      label: "尝试用户名",
      prop: "username_attempted",
      minWidth: 120,
      showOverflowTooltip: true
    },
    {
      label: "成功",
      prop: "success",
      width: 80,
      formatter: ({ success }: Record<string, unknown>) => {
        const ok =
          success === 1 ||
          success === true ||
          success === "1";
        return ok ? "是" : "否";
      }
    },
    { label: "IP", prop: "ip", width: 136, showOverflowTooltip: true },
    {
      label: "地区",
      prop: "geo_address",
      minWidth: 110,
      showOverflowTooltip: true,
      formatter: (row: Record<string, unknown>) =>
        displayIpRegion(row.ip, regionByIp.value, row.geo_address)
    },
    {
      label: "用户代理",
      prop: "user_agent",
      minWidth: 180,
      showOverflowTooltip: true
    },
    {
      label: "失败原因",
      prop: "fail_reason",
      minWidth: 140,
      showOverflowTooltip: true
    },
    {
      label: "登录时间",
      prop: "login_at",
      minWidth: 172,
      formatter: ({ login_at }: Record<string, unknown>) =>
        formatAdminUnixTime(login_at ?? null, true)
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
      const params: AdminLoginLogsParams = {
        page: pagination.currentPage,
        page_size: pagination.pageSize,
        sort: "login_at_desc"
      };
      const aid = form.adminUserId.trim();
      if (aid) params.admin_user_id = aid.slice(0, 64);
      const un = form.username.trim();
      if (un) params.username = un.slice(0, 64);
      if (form.success === "0" || form.success === "1")
        params.success = form.success;
      const ip = form.ip.trim();
      if (ip) params.ip = ip.slice(0, 45);
      const fr = form.loginAtFrom.trim();
      const to = form.loginAtTo.trim();
      if (fr) params.login_at_from = fr;
      if (to) params.login_at_to = to;

      const res = await getAdminLoginLogsApi(params);
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
        message(adminApiErrMessage(err, "管理员登录流水加载失败"), {
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
    form.username = "";
    form.success = "";
    form.ip = "";
    form.loginAtFrom = "";
    form.loginAtTo = "";
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
