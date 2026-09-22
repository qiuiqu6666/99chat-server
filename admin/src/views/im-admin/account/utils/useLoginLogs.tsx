import dayjs from "dayjs";
import { reactive, ref, onMounted, type Ref } from "vue";
import {
  getUserLoginLogs,
  mapUserLoginLogRow,
  adminApiErrMessage
} from "@/api/im-user";
import { displayIpRegion, resolveIpRegions } from "@/utils/ipRegion";
import { message } from "@/utils/message";
import type { PaginationProps } from "@pureadmin/table";

function fmtLoginAt(loginAt: string | number | null | undefined) {
  if (loginAt == null || loginAt === "") return "—";
  const n = Number(loginAt);
  if (Number.isFinite(n)) {
    const ms = n > 946684800000 ? n : n > 946684800 ? n * 1000 : Number.NaN;
    if (Number.isFinite(ms)) return dayjs(ms).format("YYYY-MM-DD HH:mm:ss");
  }
  const d = dayjs(loginAt as string);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : String(loginAt);
}

export function useLoginLogs(tableRef: Ref) {
  const form = reactive({
    userUid: "",
    loginIp: "",
    loginTimeFrom: "",
    loginTimeTo: "",
    deviceType: "" as string
  });

  const loading = ref(true);
  const dataList = ref<any[]>([]);
  const regionByIp = ref<Record<string, string>>({});
  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 30,
    currentPage: 1,
    background: true
  });

  const columns: TableColumnList = [
    { label: "ID", prop: "id", width: 88, showOverflowTooltip: true },
    { label: "UID", prop: "uid", minWidth: 110 },
    { label: "昵称", prop: "nickname", minWidth: 110 },
    { label: "终端", prop: "clientType", width: 104 },
    {
      label: "登录时间",
      prop: "loginAt",
      minWidth: 174,
      formatter: ({ loginAt }: { loginAt?: string | number | null }) =>
        fmtLoginAt(loginAt)
    },
    { label: "IP", prop: "ip", width: 138 },
    {
      label: "地区",
      prop: "region",
      minWidth: 120,
      showOverflowTooltip: true
    },
    {
      label: "设备 ID",
      prop: "hw",
      minWidth: 140,
      showOverflowTooltip: true
    },
    {
      label: "机型",
      prop: "deviceModel",
      minWidth: 130,
      showOverflowTooltip: true,
      formatter: ({ deviceModel }: { deviceModel?: string }) =>
        deviceModel == null || deviceModel === "" ? "—" : String(deviceModel)
    },
    {
      label: "App 版本",
      prop: "appVersion",
      width: 110,
      showOverflowTooltip: true,
      formatter: ({ appVersion }: { appVersion?: string }) =>
        appVersion == null || appVersion === "" ? "—" : String(appVersion)
    },
    {
      label: "结果",
      prop: "statusLabel",
      width: 100,
      showOverflowTooltip: true
    }
  ];

  async function onSearch() {
    loading.value = true;
    try {
      const uid = String(form.userUid).trim();
      const params: Parameters<typeof getUserLoginLogs>[0] = {
        page: pagination.currentPage,
        page_size: pagination.pageSize,
        sort: "login_time_desc"
      };
      if (uid) params.user_uid = uid;
      const ip = form.loginIp.trim();
      if (ip) params.login_ip = ip.slice(0, 100);
      const from = String(form.loginTimeFrom ?? "").trim();
      const to = String(form.loginTimeTo ?? "").trim();
      if (from) params.login_time_from = from;
      if (to) params.login_time_to = to;
      if (
        form.deviceType === "-1" ||
        form.deviceType === "0" ||
        form.deviceType === "1" ||
        form.deviceType === "2"
      ) {
        params.device_type = Number(form.deviceType);
      }

      const res = await getUserLoginLogs(params);
      const rows = (res.items ?? []).map(rec =>
        mapUserLoginLogRow(rec as Record<string, unknown>)
      );
      const regions = await resolveIpRegions(
        rows.map(row => row.ip as string | null | undefined)
      );
      regionByIp.value = regions;
      dataList.value = rows.map(row => ({
        ...row,
        region: displayIpRegion(row.ip, regions, null)
      }));
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
      const ax = err as { response?: { status?: number; data?: { error?: string } } };
      if (
        ax?.response?.status === 403 ||
        ax?.response?.data?.error === "forbidden"
      ) {
        message("无权限（需要 user.read）", { type: "warning" });
      } else if (ax?.response?.status === 422) {
        message(
          adminApiErrMessage(err, "日期或其它参数格式错误"),
          { type: "warning" }
        );
      } else if (ax?.response?.status !== 401) {
        message(adminApiErrMessage(err, "登录日志加载失败"), {
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
    form.userUid = "";
    form.loginIp = "";
    form.loginTimeFrom = "";
    form.loginTimeTo = "";
    form.deviceType = "";
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
